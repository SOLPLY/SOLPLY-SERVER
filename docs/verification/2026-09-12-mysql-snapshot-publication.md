# 목록 스냅샷 MySQL 발행 — 검증 리포트

2026-09-12 · 이슈 #404 · PR #405 · 브랜치 `feat/#404-auth-mysql-stats-schedules`

검증 작업자가 쓴 문서다. **main·마이그레이션·설계 문서는 한 줄도 고치지 않았다.**
커밋·푸시·머지·배포 없음.

---

## 1. 실행 결과

```
JAVA_HOME=<temurin-21.0.8> ./gradlew clean build
→ BUILD SUCCESSFUL in 8m 8s
```

`build/test-results/test/*.xml` 집계:

| | tests | failures | errors | skipped | 클래스 |
|---|---|---|---|---|---|
| 직전 기준선 | 560 | 0 | 0 | 0 | 63 |
| **이번(기동 경쟁·payload 추가)** | **564** | **0** | **0** | **0** | **65** |

늘어난 4개는 `SnapshotBootstrapRaceIT`(3)와 `SnapshotPayloadSizeIT`(1)이다.
전 항목이 실제 MySQL 8.0 Testcontainers 위에서 돌았고, H2로 대체한 경로도
환경 미비로 건너뛴 스킵도 없다(`skipped=0`).

**로그·명령**

| 무엇 | 경로 |
|---|---|
| 최종 clean build | `scratchpad/cleanbuild5.log` |
| 기동 경쟁 단독 반복 실행 6회 | `scratchpad/race_final1..6.log` |
| 기동 경쟁 계측(중복 빌드 증거) | `scratchpad/race_evi.log`, `race_two_lp.log` |
| payload 측정 | `scratchpad/payload.log` |
| 직전 560 기준선 | `scratchpad/cleanbuild4.log` |

(`scratchpad`는 검증 작업자의 로컬 실행 로그 디렉터리이며 저장소에 포함하지 않는다.)

---

## 2. 기동 부트스트랩 경쟁 (설계 §11-20) — 이번에 채운 항목

`SnapshotBootstrapRaceIT`. **실제 기동 경로를 그대로 태운다** — CAS만 떼어 보는 대체
검증이 아니라 `SnapshotScheduler.restoreOnStartup()`을 두 노드에서 진짜로 부른다.

**어떻게 두 노드를 만들었나.** 노드를 가르는 상태는 설치자가 들고 있는 것이 전부이므로
(`SnapshotBox`·표시값 홀더·태그 홀더·`CacheWriteLock`), 그 넷과 `SnapshotScheduler`·
`PlaceListSnapshotProperties`·`LockProvider`를 노드마다 새로 만들고 리포지토리·발행 서비스·
`DataSource`는 공유했다. **공유되는 심판은 `shedlock` 테이블과 포인터 행**이고, 그것이
"두 프로세스"의 실질이다. `LockProvider`는 운영에서 JVM마다 하나이므로 노드마다 따로 줬다
(한 인스턴스를 공유해도 결과가 같은 것은 확인했다 — §4).

**무엇을 확인했나**

1. 승자가 락 안에서 원본을 읽는 동안 붙잡아 창을 실제로 벌리고, 그 시점에
   **발행물 0행 · 두 노드 모두 설치 id 음수 · 두 기동 스레드 모두 미완료**를 단언한다
   → *복원 전에는 준비 완료가 아니다.*
2. 풀어 준 뒤: **발행물은 정확히 1행**, 두 노드의 설치 발행물 id가 같고,
   **커서 회차가 같고**, 정렬 배열의 원소 집합과 표시값·태그가 같다.
3. 발행물이 이미 있으면 기동은 **원본에서 다시 짓지 않고 복원만** 한다(빌드 횟수 0으로 확인).
4. 남이 락을 쥔 채 발행하지 못하면 기동은 조용히 성공하지 않고
   **`IllegalStateException`으로 기동을 막는다**(timeout 경로). 그 뒤 설치된 것이 없고
   발행물도 남지 않는다.

단독 반복 6회 전부 통과했다.

---

## 3. payload 크기와 왕복 시간 — 고정 픽스처, 성능 주장 아님

`SnapshotPayloadSizeIT`. 난수 없는 **고정 합성 픽스처**(이름·썸네일 키·점수를 인덱스로
흔들어 gzip이 비현실적으로 잘 접지 않게 했다). 최종 clean build 실행에서 나온 값이다.

| 엔트리 | 태그 | 압축 전 JSON | 저장 바이트(gzip) | 비율 | encode | load(DB 왕복) | decode |
|---|---|---|---|---|---|---|---|
| 1,000 | 40 | 356,422 B | **43,505 B** | 12.21% | 3.4 ms | 2.0 ms | 1.5 ms |
| 10,000 | 40 | 3,548,602 B | **422,779 B** | 11.91% | 31.6 ms | 5.0 ms | 13.1 ms |

시간은 5회 중 **최소값**이다(첫 회의 JIT·클래스 로딩을 걷어내려는 것뿐이다).

**이 수치를 운영 규모 예측에 쓰지 말 것.** 개발 노트북의 Testcontainers MySQL,
같은 JVM, 픽스처 하나로 잰 값이다. 운영의 상호명·썸네일 키 길이 분포가 다르면 압축비가
그대로 달라지고 디스크·네트워크도 다르다. 장소당 약 43바이트(gzip)라는 산술만 적어 두고,
`max_allowed_packet`(MySQL 8 기본 64MB) 대비 여유는 **배포 뒤 발행 로그의 `bytes=`를
직접 보는 것**이 정본이다(계획서의 배포 전 확인 항목).

단언은 시간이 아니라 정합성에만 걸었다 — 되읽은 payload의 엔트리 수·첫/끝 엔트리·태그 수가
넣은 것과 같은지. 시간에 임계값을 걸면 기계가 바쁠 때마다 빨개지는 테스트가 된다.

---

## 4. 관측 — 같은 JVM 하네스에서 후보 빌드가 두 번 돌았다

**무엇을 봤나.** 두 노드를 같은 밀리초에 기동시키면 **둘 다 원본을 읽어 후보를 짓고 발행을
시도하는** 회차가 나온다. 5회 중 4회다. 애플리케이션 로그에
`발행된 목록 스냅샷이 없어 최초 발행을 짓는다`가 한 회차에 두 번 찍히고, 진 쪽은
`최초 발행을 놓쳤다 - 그 사이 다른 발행이 있었다`로 물러난다.

빌드 진입 시점을 계측해 찍은 줄(`race_evi.log`):

```
### BUILD#2 thread=pool-5-thread-1 publicationRows=0 pointer=-1 shedlock=until=12:33:14.98 now=12:23:15.009
### BUILD#1 thread=pool-5-thread-2 publicationRows=0 pointer=-1 shedlock=until=12:33:14.98 now=12:23:15.009
```

노드마다 `LockProvider`를 따로 줘도 같았다(`race_two_lp.log`).

**원인은 규명하지 못했다.** 두 방향의 설명이 나왔지만 **어느 쪽도 결론이 아니다.**

- *"두 conditional UPDATE가 모두 1행을 고쳤다"* — 그런 문장이 두 번 성공했다는 기록이 없다.
  게다가 ShedLock 6.3.1의 획득은 조건부 UPDATE 하나가 아니다. `StorageBasedLockProvider.doLock`은
  이름이 자기 `LockRecordRegistry`에 없으면 `INSERT IGNORE`를 먼저 돌리고 성공하면 그대로
  획득한다. 이 하네스는 노드마다 새 `LockProvider`를 만들어 레지스트리가 비어 있으므로,
  첫 시도는 두 노드 다 INSERT 경로다. 즉 인용된 UPDATE가 그 회차에 실행됐는지부터 확인되지 않았다.
- *"두 줄의 `lock_until`이 같으니 락 행이 한 번만 쓰였고, 따라서 두 번째 획득은 없었다"* —
  획득에 성공하면 `lock_until`을 다시 쓰는 것은 맞지만, 위 줄의 값은 애플리케이션이 따로 읽어
  찍은 것이라 **읽은 시점과 획득 시점의 선후를 이 로그만으로 확정할 수 없다.**

둘을 가르려면 문장 수준의 증거가 필요하다 — 획득 직후 `shedlock` 한 행 전체
(`lock_until`·`locked_at`·`locked_by`)를 찍고, 노드마다 `withLockedByValue`로 주체를 갈라
다시 재현하는 것이다. **이 작업에서는 하지 않았다.**

**하네스가 운영과 다른 점 둘을 기록해 둔다** — 원인이라는 주장이 아니라, 재현에서 먼저 배제할
후보라는 뜻이다.

- 세 `LockProvider`(컨텍스트 빈 + 노드 둘)가 전부 같은 `locked_by`를 쓴다. 기본값이 호스트명이고
  셋이 한 JVM에 있기 때문이다. ShedLock의 해제 문장은 `WHERE name = ? AND locked_by = ?`뿐이라
  이 셋이 서로 구분되지 않는다. 운영의 두 인스턴스는 호스트가 달라 갈린다.
- 같은 JVM에 락을 잡지 않고 발행 경로를 부르는 테스트 코드가 있다(`SnapshotRebuilder`,
  `SnapshotPublicationIT`의 `publishRound()` 직접 호출). 계측이 `buildFromSource()`에 걸려 있으면
  그 호출도 "BUILD#"로 세어진다.

운영 경로에서 `buildFromSource()`를 부르는 자리는 둘뿐이고 둘 다 락 안이다 —
`SnapshotScheduler.publishBootstrap()`(직접 `lockProvider.lock`)과
`SnapshotPublisher.publishRound()`(`@SchedulerLock`).

**정확성은 깨지지 않았다.** 관측된 회차에서도 발행물은 하나만 남는다. 보장의 주체가 락이 아니라
**포인터 CAS**이기 때문이다 — 진 쪽은 `StalePublicationBaseException`을 받고 이긴 쪽의 발행물을
설치한다. §2의 단언(발행물 1행 · 두 노드의 설치 발행물 id 동일 · 커서 회차 동일 · 정렬 배열과
표시값·태그 동일)이 **전부 그대로 통과했다.**

**대가는 중복 비용이다.** 함께 뜬 인스턴스 수만큼 원본 전량 읽기와 인코딩이 헛돌 수 있다.
10,000 엔트리 기준 encode만 30ms대이고 원본 읽기가 별도로 붙는다.

**그래서 테스트는 "한 노드만 짓는다"를 단언하지 않는다.** 관측이 그 반대를 보였고, 그 성질이
운영에서 성립하는지는 위 미규명 때문에 아직 말할 수 없다. 대신 이 경로가 실제로 지키는 것
(발행물 하나 · 두 노드 동일 복원)만 문다. 판단은 메인 담당자 몫으로 남긴다.

---

## 5. 남은 한계

- **§4의 원인 미규명.** 중복 후보 빌드가 왜 났는지 좁히지 못했다. 획득이 INSERT 경로였는지
  UPDATE 경로였는지조차 확정하지 못했으므로, 다음 사람이 여기서 이어받으려면 **문장 수준 계측**
  (획득 직후 `shedlock` 한 행 전체)과 **노드별 `locked_by` 분리**부터 해야 한다. 별도 작업이다.
- **기동 복원이 최초 발행 직후 한 폴 간격만큼 잠든다.** `restoreOnStartup`이
  `tryBootstrap()` 성공 뒤 곧바로 설치하지 않고 `sleepOnePoll()`을 탄다 — 기본값 5초에서는
  기동이 그만큼 늦는다. 테스트 설정은 `adopt-poll-interval-ms`를 1500ms로 잡아 피했다.
- **컨텍스트 종료 시점의 폴이 ERROR를 남긴다.** 닫히는 중인 컨텍스트의 설치 폴이
  `CannotCreateTransactionException`으로 실패해 실행마다 100줄 남짓 찍힌다.
- **`§11-9-1`(어드민 flush)은 동시성이 아니라 순서 관측으로 검증한다.**
  요청 행이 단일 핫 행이라 두 트랜잭션이 같은 순서로 잡으면 직렬화될 뿐 데드락이 나지 않고,
  데드락은 경로마다 순서가 갈릴 때만 생긴다. 그래서 `RebuildRequestFlushIT`가
  `StatementInspector`로 "요청 행을 잠그기 전에 엔티티 UPDATE가 나갔는가"를 직접 본다
  (`flush()`를 빼면 곧바로 빨개진다). **이 스위트는 데드락이 불가능함을 증명하지 않는다.**
- **테스트 하네스 사정 둘.** 컨텍스트 캐시가 스무 개 남짓 살아 있어 Gradle 기본 512m으로는
  `OutOfMemoryError`가 나므로 test 태스크에 `maxHeapSize = '2g'`를 뒀다. 그리고 Gradle
  8.14.2는 Java 25 데몬에서 빌드 스크립트를 파싱하지 못하므로(`Unsupported class file
  major version 69`) `JAVA_HOME`을 JDK 21로 두고 실행해야 한다.
