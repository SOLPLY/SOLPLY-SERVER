# 목록 스냅샷 MySQL 발행 Implementation Plan

> **상태: 구현·테스트·검증 완료** (2026-09-12). `./gradlew clean build` **564개 통과**
> (실제 MySQL 8 Testcontainers, 스킵 0). 커밋·푸시·머지·배포는 아직 하지 않았다.
>
> 실행 결과·수치·남은 한계는 **[검증 리포트](../../verification/2026-09-12-mysql-snapshot-publication.md)**
> 가 정본이다. 이 계획서는 **무엇을 왜 그 순서로 지었는가**의 기록으로 남긴다.

**Goal:** 목록 스냅샷의 **내용**을 MySQL에 발행해 모든 인스턴스가 같은 회차를 복원하게 하고,
배포가 회차를 갈아치우지 않게 한다. 재빌드 요청은 통계 트랜잭션과 같은 트랜잭션에 남는 durable
카운터로 바꾼다.

**Architecture:** 통계·어드민 트랜잭션이 요청 카운터를 올린다 → 발행자(공유 ShedLock)가 요청을
코얼레싱해 원본에서 짓고 **한 트랜잭션**에서 payload INSERT · 커서 회차 확정 · 포인터 CAS ·
처리 표시를 끝낸다 → 모든 인스턴스가 명시 포인터를 폴해 바뀐 것만 내려받아 `CacheWriteLock`
안에서 재확인 후 설치한다. 기동은 짓지 않고 복원한다.

**Tech Stack:** Java 21, Spring Boot 3.3.5, MySQL 8, Flyway, JdbcTemplate, Jackson(+GZIP),
ShedLock.

**Spec:** [../../design/2026-09-12-stats-commit-snapshot-and-db-delta.md](../../design/2026-09-12-stats-commit-snapshot-and-db-delta.md)
— 스키마·형식 계약·한계·검증 34항목의 정본이다. 이 계획은 **작업 순서와 트랜잭션 경계**만 적는다.

---

## Global Constraints

- 커밋·푸시·머지·배포 금지. 워크스페이스는 `feat/#404-auth-mysql-stats-schedules` 하나.
- **인증(`domain/auth`, `domain/admin/auth`, `global/jwt`, V41)은 손대지 않는다.**
- **V42와 그에 딸린 미커밋 구현을 보존한다.** 이 계획은 그 위에 얹는다.
- **적용된 V39·V41·V42를 편집하지 않는다.** 신규는 V43 하나. **V39를 DROP하지 않는다.**
- `src/test/**`를 읽지도 고치지도 실행하지도 않는다. 설정 키 변경은 보고로 넘긴다.
- 조회 알고리즘·집계 SQL·인기점수·안전망·네 회차 cron을 재설계하지 않는다.
- 인증 외 Redis 인프라를 건드리지 않는다. 지우는 것은 스냅샷 갱신 초안 셋과 버전 발급소뿐이다.
- 새 스레드 풀·비동기 실행을 만들지 않는다.
- `application*.yml`·`gradle.properties`의 비밀값을 출력하지 않는다.
- 측정하지 않은 성능 주장을 쓰지 않는다.

---

## 리뷰에서 고친 것 — 두 라운드

### 1차: Redis 초안 → MySQL 발행 (여섯)

| # | 초안 | 구현 |
|---|---|---|
| 1 | `SnapshotVersionIssuer`(V39)로 버전 발급 | **발행물 INSERT의 AUTO_INCREMENT id가 신원.** 발급소 삭제, V39 테이블은 물리적으로 존치. 명시 포인터(≠`MAX(id)`). 커서 회차는 발행 id와 분리 |
| 2 | 후보를 다 만든 뒤 포인터를 읽어 CAS | **빌드 "전"에 기준 포인터를 잡는다.** 뒤에 읽으면 낡은 후보가 새 내용을 덮는다. `version <=` 조건 폐기 |
| 3 | `markProcessedIfUntouched(mySeq)` | 앞 조건 추가: `processed_seq = mySeq - 1` **AND** `requested_seq = mySeq`. 밀린 남의 요청을 대신 닫지 않는다 |
| 4 | 리포지토리마다 `REQUIRES_NEW`, 발행과 처리 표시가 별개 | **발행 서비스 하나가 트랜잭션을 소유**하고 INSERT·회차·CAS·처리 표시가 한 트랜잭션. `request()`는 `MANDATORY` |
| 5 | 어드민이 먼저 로컬 패치 후 발행 | **발행 성공 뒤에만 로컬 설치.** 로더를 순수 읽기로 분해, 설치는 `CacheWriteLock` 안에서 발행 id 재확인 |
| 6 | 모르는 필드 무시 = 호환 | **모든 필드 필수**, **primitive에 null 금지**, `entry_count` 대조, 단위·정렬 계약 명시 |

### 2차: 승인과 함께 온 여섯 (전부 구현에 반영됨)

| # | 무엇 | 어디에 |
|---|---|---|
| 1 | 발행 트랜잭션이 `REQUIRED`면 어드민 `afterCommit`에서 끝난 트랜잭션에 다시 붙어 커밋되지 않는다 | `SnapshotPublicationService` → `REQUIRES_NEW` + `READ_COMMITTED` |
| 2 | RC에서는 SELECT 둘을 트랜잭션으로 묶어도 시야가 문장마다 갈린다 | 포인터+payload를 **한 JOIN 문장**으로 읽는다 |
| 3 | 정리 실패가 발행 실패로 번지면 안 된다 | `cleanUpQuietly` — 발행 트랜잭션 밖, 로그만 남기고 다음 회차가 재시도 |
| 4 | `AUTO_INCREMENT=1e9`와 배포 전 `MAX` 확인은 보장이 아니라 절차만 늘린다 | 시작값 지정 제거. 분리는 커서 `v7`이 한다. `MAX(id)` 서술도 "롤백된 행" → **"발급 순서 ≠ 커밋·채택 순서"**로 고침 |
| 5 | 필드 추가라도 순서·필터 해석을 바꾸면 호환이 아니다 | 형식 규칙에 명시 + 장소/태그 id 중복·비유한 `popularScore` 거절 |
| 6 | "순환 없음/짧다"를 실제 호출 자리로 확인할 것 | **확인 결과 실제 위험이 있었다** — 아래 |

**6이 실제 결함이었다.** 어드민 여섯 호출 자리가 전부 엔티티 변경(장소 삭제·태그 수정·이미지
교체)을 들고 요청 증가에 온다. JPA는 그 UPDATE/DELETE를 커밋까지 미루므로 실제 쓰기 순서가
**요청 행 → `places`/`tags`**가 되고, 같은 장소를 걸친 두 어드민 트랜잭션이 서로의 락을 마주
본다. 그래서 `SnapshotRebuildRequestRepository.request()`가 **먼저 `EntityManager.flush()`를
부른다** — 그 트랜잭션이 마지막으로 잡는 행을 요청 행 하나로 고정하는 장치다. 통계 경로는
네이티브 SQL만 쓰므로 flush할 것이 없다.

---

## 실제로 만든 것

**신규 (13)**

| 파일 | 책임 |
|---|---|
| `db/migration/V43__place_list_publication.sql` | 발행물 · 명시 포인터 · 재빌드 요청. **V39를 DROP하지 않는다** |
| `cache/publication/SnapshotPayload.java` | 발행 DTO. 전 필드 `required = true` |
| `cache/publication/SnapshotPayloadCodec.java` | JSON+GZIP · SHA-256 · 형식·개수·중복·수치 검증 |
| `cache/publication/PublishedSnapshot.java` | 내려받은 한 벌 |
| `cache/publication/PublicationCandidate.java` | 발행 후보. `carriedCursorVersion == null`이 구조 발행 |
| `cache/publication/RebuildRequestCounters.java` | `requestedSeq`/`processedSeq` |
| `cache/publication/ProcessedMark.java` | 처리 표시 정책 셋(`upTo`·`solelyMine`·`none`) |
| `cache/publication/StalePublicationBaseException.java` | CAS 패배 → 트랜잭션 롤백 신호 |
| `cache/publication/SnapshotPublicationRepository.java` | 포인터 폴 · JOIN 내려받기 · INSERT · CAS · 정리 |
| `cache/publication/SnapshotRebuildRequestRepository.java` | 요청 증가(`MANDATORY`+flush) · 카운터 · 표시 둘 |
| `cache/publication/SnapshotPublicationService.java` | **발행 트랜잭션 소유자**(`REQUIRES_NEW`) |
| `cache/SnapshotInstaller.java` | 내려받기 → 검증 → 복원 → 락 안 재확인 후 설치 |
| `cache/SnapshotPublisher.java` | 요청 폴 → 기준 → 빌드 → 발행 → 정리. 공유 ShedLock |

**수정 (14)** — `SnapshotScheduler`(기동 복원·설치 폴), `SnapshotRefresher`(어드민 훅 재작성),
`SnapshotLoader`(순수 읽기로 분해), `SnapshotBox`·`Snapshot`·`CacheWriteLock`(계약 서술),
`SortedPlaces`(`entries()`), `PlaceViewHolder`·`TagViewHolder`(`all()`),
`PlaceListSnapshotProperties`(키 셋), `PlaceListCursor`(`v6`→`v7`),
`PlaceStatsBatchProcessor`·`BookmarkCountDeltaProcessor`(요청 증가),
`AdminPlaceService`·`PlaceImageFieldUpdater`·`AdminTagService`(트랜잭션 안 요청 증가),
`PlaceStatsFacade`(서술).

**삭제 (4)** — `SnapshotVersionIssuer`, `SnapshotRefreshSignal`, `SnapshotRefreshNotifier`,
`SnapshotRefreshListenerConfig`.

---

## 확인한 것

```
./gradlew compileJava                                     → BUILD SUCCESSFUL
rg -n "SnapshotRefreshSignal|Notifier|ListenerConfig|SnapshotVersionIssuer" src/main/   → 없음
rg -ni "redis" src/main/java/org/sopt/solply_server/domain/place/          → 없음
grep -rn "place_list_snapshot_versions" src/main/java/     → 커서 javadoc의 전환 설명 한 줄뿐
ls src/main/resources/db/migration/V39* src/main/resources/db/migration/V43*                                  → V39 그대로, V43 신규
git status --short src/test/                               → 세션 시작 시점과 같은 두 줄
```

(이 구간은 **구현 작업자**가 확인한 것이다. 테스트는 그 뒤 별도 작업자가 썼다.)

---

## 테스트 작업자가 한 것

설계 문서 §11의 34항목을 정본으로 스위트를 짰고, `./gradlew clean build`가 **564개 통과**했다
(실제 MySQL 8 Testcontainers, H2 대체 없음, 스킵 0). 리뷰가 짚었던 결함의 회귀는 전부 들어갔다 —
**11**(기준을 빌드 전에 잡는가), **13**(발행과 처리 표시가 한 트랜잭션인가), **28**(발행 전에
로컬을 고치지 않는가), **29**(남의 요청을 닫지 않는가), **9-1**(어드민 flush).

- **9-1은 `flush()`를 빼면 빨개진다.** `RebuildRequestFlushIT`가 `StatementInspector`로
  "요청 행을 잠그기 전에 엔티티 UPDATE가 나갔는가"를 직접 본다. 다만 이 스위트는 **데드락이
  불가능함을 증명하지는 않는다** — 순서 관측이다(검증 §5).
- 동시성 항목은 독립 커넥션과 래치로 실제 경쟁을 만들었다.
- **설정 키를 고쳤다.** `application-test.yml`이 `publish-poll-interval-ms: 3600000`(사실상
  정지)과 `adopt-poll-interval-ms: 1500`을 쓴다. 채택 폴을 크게 잡지 않은 것은 최초 부트스트랩이
  발행 직후 한 채택 폴만큼 자기 때문이다.
- **열린 채로 남긴 것 하나 — 동시 부트스트랩에서 후보 빌드가 두 번 돈 회차가 관측됐다**
  (검증 §4, 5회 중 4회). 발행물은 하나만 남고 §11-20은 통과했지만, **원인은 규명하지 못했다.**
  이어받으려면 획득 직후 `shedlock` 한 행 전체를 찍는 문장 수준 계측과 노드별 `locked_by` 분리가
  필요하다.

---

## 배포 전에 확인할 것

- **`payload_bytes`를 실제로 볼 것.** 발행 로그의 `bytes=`와 서버 `max_allowed_packet`
  (MySQL 8 기본 64MB), JDBC `maxAllowedPacket`을 대조한다. **이 문서는 크기도 압축비도 예측하지
  않는다.**
- **이 배포에서 진행 중 커서가 만료된다.** 커서 형식이 `v7`로 올라가고, 옛 인스턴스는 발행물 없이
  힙에만 스냅샷을 들고 있다. 다음 배포부터 이어진다.
- **V39 테이블은 남는다.** 롤백 창이 닫힌 뒤 별도 마이그레이션으로 지운다.

---

## 열려 있는 선택

**A. 어드민 훅이 payload를 직접 발행한다**(채택). 어드민 쓰기가 전량 원본 집계를 부르지 않게
하려는 선택이고, 대가는 직렬화 + 재내려받기가 어드민 요청 스레드에 붙는 것이다. payload가
커지면 여기가 먼저 아프며, 그때는 훅에서 발행을 떼고 요청만 남기면 된다.

**B. 발행자가 자기 힙을 그 자리에서 갈지 않는다**(채택). 최대 한 폴 간격의 자기 지연을 받는
대신 설치 경로가 하나다.

**C. 어드민이 발행 성공 뒤 다시 내려받는다**(채택). 방금 만든 후보를 그대로 설치하면 왕복이 하나
줄지만, "DB에 있는 것을 코덱으로 되읽은 것이 설치된다"는 성질을 잃는다.

**D. 발행자의 재빌드는 언제나 구조 발행이라 통계 회차마다 커서가 만료된다.** 옛 10분 무조건
재빌드도 같은 일을 했고 통계 회차는 시간 단위지만, **줄었다고 주장하지 않는다** — 세어 본 적이
없다. 없애려면 엔트리 집합 비교가 필요한데 그 비용과 이득을 재지 않았다.

**E. V39 DROP은 이 브랜치에서 하지 않는다.**
