# 단계 4·5 보고 — 동네 캐시 TTL·추정 바이트 상한·적재 실행기, 벤치 앱 확인

캠페인: `load-test/campaigns/2026-09-24_town-retention-memory/README.md`(메인 결정 절). 작성 2026-09-24, Claude Opus 5.5. 커밋·푸시 없음.

## 1. 구현 (제품)

| 파일 | 변경 |
|---|---|
| `config/PlaceListTownCacheProperties` | `maxPlaces`·`loaderThreads` 삭제. `expireAfterWrite=65m`, `maxEstimatedBytes=64MiB` 추가 |
| `cache/town/TownPlaces` | 생성할 때 한 번 `estimatedBytes`를 계산한다(아래 식). 정렬 배열 관련 낡은 주석(실행 상한은 다음 단계, 예산이 정렬을 묶는다) 삭제 |
| `cache/town/TownPlacesCache` | Caffeine에 `expireAfterWrite`(최신·과거 번호 공통, 읽어도 연장 안 됨), `maximumWeight(maxEstimatedBytes)`, `weigher=estimatedBytes` 적용. 가짜 시계용 package 생성자, 검증용 `weightedSize()` 추가. 축출 카운터는 `SIZE`만 센다 |
| `cache/town/TownLoadRegistry` | 고정 4스레드 풀을 **상한 없는 캐시 풀**로 교체(`newCachedThreadPool`, 데몬 플랫폼 스레드). 그 밖의 동작은 그대로: 동일 작업 공유, 호출자별 `copy()`, 실패 즉시 전달, 종료 절차 |
| `service/PlaceListRequestOrchestrator` | "풀 넷" 주석 한 줄만 고침(기존 dirty 변경은 건드리지 않음) |

- 실행기: 처음 지시는 가상 스레드였다. JDBC 드라이버에 `synchronized`가 있어 문의했고, 메인이 캐시 풀로 바꿨다. 이 우려는 측정한 것이 아니다.
- 추정식: 동네당 1,024 B를 두고, 장소마다 아래를 더한다.
  - `152`(PlaceEntry 80 + PlaceView 32 + 맵 슬롯 16 + 원소 배열 4 + **정적 5축 20을 처음부터 잡음**)
  - Long·Double 박싱 24씩(Long 캐시 범위 안의 값은 0)
  - 문자열 `24 + align8(16 + 2×글자 수)`(전부 UTF-16으로 가정)
- 추정은 압축 참조 레이아웃을 가정한 값이며 **힙 바이트가 아니다**. 그래프를 걷지 않고, 새 의존성도 없다.
- 새 필드 때문에 `TownPlaces` 객체가 48 B에서 56 B로 늘었다(히스토그램 3,752 B / 67개). 1단계 JOL 값(2.11 MiB/벌)은 변경 전 구조를 잰 것이다. 차이는 동네당 8 B로 무시할 만하다.

## 2. 테스트 (변경 완료 후 관련 범위 1회 + 고친 클래스 1회)

- 1차 `./gradlew test --tests 'org.sopt.solply_server.domain.place.cache.town.*'` → **147개 중 2개 실패**. 원본: `results/tests/1-town-package-first-run.txt`
  - `TownPlacesLazyOrderTest.실패한_생성은_자리를_물고_남지_않는다`: NPE. **내 변경이 원인**이다. 이 테스트는 일부러 `null` 원소를 넣는데, 추정 계산이 그것을 역참조했다. 추정에서 `null` 원소는 고정비만 세도록 고쳤고, 재실행하니 이 클래스 13/13 통과. 원본: `results/tests/2-lazyorder-rerun.txt`
  - `DbDirectHttpArmIT.태그_필터가_걸린다`: 기대 `[340]`인데 실제 `[]`. `DB_DIRECT` 경로라 이번에 바꾼 파일을 지나지 않는다. **조사하지 않았고 미해결로 남긴다.**
- 추가·수정한 검증:
  - `TownPlacesCacheTest`: 가짜 시계로 64분 59초에는 남고 65분이 넘으면 두 번호 모두 빠짐, 읽어도 연장 안 됨. 추정 바이트 상한, 빈 동네도 0보다 큰 무게. 배열 없는 모양과 5축 모양의 무게가 같고 배열이 나중에 붙어도 `weightedSize`가 그대로임. 벤치 모양의 추정 ≥ 350.1 B/장소
  - `TownPlaceListServiceCursorTest`: 기간이 지난 최신 번호는 다시 적재해 이어 감. 기간이 지난 과거 번호 커서는 만료되고, 새로고침은 최신 번호로 답함
  - `TownLoadRegistryTest`: 운영 실행기로 서로 다른 적재 6개가 막힌 채 **동시에 시작**, 같은 키는 합류. 기존 공유·취소 격리·실패 재시도 테스트 유지
- 이후의 주석 정리(TownPlaces, TownLoadRegistry)는 동작 변경이 없어 재실행하지 않았다.

## 3. 벤치 앱 확인

환경:
- 현재 트리로 `bootJar`(주석 정리 전 jar, 동작 동일) 후 compose로 다시 띄웠다.
- 실제 자원 확인(`docker inspect`): app 2 CPU/2 GiB, `-Xms=-Xmx 1280m`, G1, `-Xlog:gc`. mysql 2 CPU/**3 GiB**(1.5 GiB였던 컨테이너를 compose 값으로 재생성, 볼륨은 유지).

기동 전제 조치(벤치 전용):
- **V48 checksum 불일치**로 기동 실패. 적용 후 파일의 주석만 바뀐 것이었다. SQL 본문과 실제 스키마가 같음을 확인(`results/flyway-v48-equivalence.txt`)하고, 원래 행을 백업(`results/flyway-v48-row-before.tsv`)한 뒤 checksum만 갱신했다(`results/flyway-v48-repair.txt`). **검증은 켠 상태로 기동**했고 V49가 적용됐다. (중간에 검증을 끄고 띄운 적이 있으나 최종 환경이 아니다.)
- `management.health.redis.enabled=false`(Redis 없음).
- `users.csv` 토큰이 만료돼 있었고, 공용 생성기는 현재 필수 클레임을 싣지 않았다. 캠페인 사본 `bench/gen-users.mjs`로 `fid`·`ver`·`iss`·`aud`를 넣어 다시 발급했다(토큰은 출력하지 않음).

실행: `bench/run.sh` → `results/run-20260924-185339/`.
- 시나리오 `scenarios/list-120.yml`: 도착률 100/s × 1.2요청으로 약 120 req/s. warm 20초 + 측정 60초.
- 요청 구성: 동네 40%, 시(201) 20%, 태그 20%, 스크롤 20%(p1 → 1초 → p2). A와 B의 스캔 의미는 같다.
- 번호 증가는 벤치 DB의 `place_list_town_versions`를 직접 올렸다. **집계 배치를 돌린 것이 아니다.**

| 구간 | 요청(80초) | 처리율 | 응답 | p50/p95/p99 (ms, warm 포함) | 적재(시작=완료) | 실패·예산 초과·축출 |
|---|---|---|---|---|---|---|
| 적재 직후 | — | — | — | — | 67 | 0 |
| A 평상시(현재 1벌) | 9,519 | 약 119/s | 전부 200 | 6 / 308 / 743 | +0 | 0 |
| [합성] 9회 증가 × 전 동네 적재 | — | — | 비정상 0 | — | +594 → 누계 661 | 0 |
| B 10벌 보관 + 측정 중 4회 증가 | 9,605 | 약 120/s | 전부 200 | 6 / 10 / 16 | +239(합류 41) | 0 |

- A는 새로 뜬 JVM의 첫 부하라서 JIT와 기동 GC가 섞였다. 첫 10초 창 p95 87 ms, Mixed pause 172 ms가 있었다. **A와 B의 지연을 비교하지 않는다.**
- 앱 CPU(3초 샘플, 200%가 상한): A 평균 78.5%/최대 202%, B 평균 38.6%/최대 46.7%. MySQL CPU는 평균 약 18%, 커넥션 11.
- 로컬 참고값이며 SLO가 아니다.

**10벌 보관은 실제로 확인했다:**
- 진단 GC 히스토그램 기준 `PlaceEntry` 수:
  - A 뒤 12,640 = 전역 스냅샷 6,320 + 동네 1벌
  - 합성 후 69,520 = 6,320 + **10 × 6,320**, `TownPlaces` 661개
  - B 뒤 93,420, `TownPlaces` 900개
- 번호: 합성 후 10~13, B 뒤 14~17. 증가 기록은 `bumps.log`의 13줄.
- 합성 보관은 저장 규모를 검증한 것이고, 65분 TTL의 시간 동작은 가짜 시계 테스트로만 확인했다.
- 일정에서 나온 10벌은 시나리오일 뿐 보장된 상한이 아니다(관리자 수정·지연).

**옛 커서가 이어진다:**
- 합성 중 번호마다 잡아 둔 시 단위 커서 9개(과거 번호 9벌)를 B가 끝난 뒤 다음 페이지로 요청했다. **9/9 모두 200이고 각각 20건**이다.
- B 부하 중 `scroll-p2` 1,605건도 모두 200이었다. 다만 그중 증가를 가로지른 건수는 따로 세지 않았다.

**재적재 중첩:**
- B 측정 창에서 증가 4회에 적재 239회가 모두 성공했다. 같은 키에 대한 합류가 41회(미스 280 − 적재 239)였고 실패는 0이다.
- 적재 풀이 늘면서 live 스레드가 72에서 92로 늘었다. 쉬는 스레드는 60초 뒤 사라진다.

**GC 뒤 힙(강제 GC는 부하 창 밖, `jcmd GC.class_histogram`을 사이드카로 실행):**

| 시점 | live heap | 컨테이너 memory.current |
|---|---|---|
| A 뒤(동네 1벌 + 전역) | 86.8 MiB | 1.611 GiB / 2 GiB |
| 합성 10벌 | 104.8 MiB (+18.0, 벌당 약 2.0 MiB) | 1.612 GiB |
| B + 커서 뒤(900 항목) | 113.0 MiB | 1.615 GiB |

- 컨테이너 메모리는 AlwaysPreTouch로 힙 1280m을 이미 잡아 둔 상태라, 캐시 변화가 거의 드러나지 않는다.
- 자연 발생 GC는 B 동안 Young 10회, 합계 0.101 s였고 Mixed·Full은 없었다. Full GC 3회는 모두 진단 때문이다(`gc.log`).

**추정 대 실측(별개의 값):**
- 현재 데이터 1벌의 추정 무게는 2,351,256 B(2.24 MiB, 372 B/장소)다(DB 값에 추정식을 적용해 계산).
- 비교할 실측은 두 가지다. 변경 전 JOL은 5축 전부 2.11 MiB였다. 앱의 GC 뒤 증가분은 벌당 약 2.0 MiB였다(인기순 배열만 생성된 상태).
- 64 MiB 상한은 이 데이터 기준 약 28벌에 해당한다. 실험 끝의 900항목은 약 32 MB로 계산되어 축출 0과 맞는다.

## 4. 남은 것·한계

- `DbDirectHttpArmIT` 태그 필터 실패는 미조사다.
- 공용 `load-test/shared/generate-users-csv.mjs`는 현재 토큰 형식에 맞지 않는다. 캠페인 사본으로만 우회했다.
- 벤치 DB에 남은 변경: V48 checksum 갱신, V49 적용, 번호 증가(현재 14~17). 벤치 스택은 떠 있다.
- 산출물(무시 경로 `load-test/`): `bench/{run.sh,gen-users.mjs,heap-poller.sh,diag-heap.sh}`(뒤의 둘은 2026-09-10 캠페인 사본), `scenarios/list-120.yml`, `results/{run-20260924-185339,diag,metrics,tests}`, `results/flyway-v48-*`.
