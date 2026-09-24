# 보고 — DB 로더 vs Redis JSON: 같은 버전의 로컬 적재 비용

캠페인: `load-test/campaigns/2026-09-24_redis-town-load/README.md` (메인 소유 실행안).
작성 2026-09-24, Claude Opus 5.5. 커밋·푸시 없음. 제품 코드·운영 설정·공용 compose 변경 없음. 제품 빌드(gradle) 재실행 없음.

## 1. 무엇을 돌렸나

**하니스 형상**
- 제품 jar(`build/libs/solply-server-0.0.1-SNAPSHOT.jar`, sha256 `c8afc211…a44712`)를 그대로 쓴다.
  - bench-app 이미지(`54ccc474`) 안의 jar와 해시가 같다.
  - jar 빌드 뒤 수정된 `TownPlaces`·`TownLoadRegistry`는 다시 컴파일해 보니 바이트코드가 jar와 같았다(주석만 바뀜).
- 측정 전용 클래스 하나(`bench/src/.../cache/town/TownLoadBench.java`)를 `PropertiesLauncher`의 `loader.path`로 붙인다.
  - 같은 패키지라 package-private `members()`·`order()`를 쓸 수 있다.
- 앱 전체 컨텍스트(`SolplyServerApplication`)를 띄운다. `@Scheduled` 등록기 빈 하나만 빼서 배치가 돌지 않게 했다.
  - 기동 로그에서 `[bench] @Scheduled processor removed`를 확인했다.
- 스크립트:
  - `bench/build-harness.sh`: jar에 대고 javac로 컴파일한다.
  - `bench/run.sh <label>`: 실행한다.
  - `bench/table.py`: 결과를 표로 만든다.

**두 경로의 작업 단위** (동네 하나 = 완성된 `TownPlaces` + 인기순 배열까지)
- DB: 제품 `TownSourceLoader.load(List.of(town))`를 부른다. 안에서 RR 읽기 전용 트랜잭션, 버전 조회, 네이티브 행 조회, Hibernate 결과를 객체로 구성하는 과정이 다 돈다. 그다음 `order(POPULAR)`.
- Redis: GET 한 번 → Jackson으로 명시적 DTO를 역직렬화 → 제품 팩토리 `TownPlaces.objectsOnly` → `order(POPULAR)`.
  - payload는 JSON, 압축 없음, 필드 이름을 줄이지 않았다. 버전과 필터·정렬·표시 필드를 모두 담는다.
- 공통 조건:
  - 두 경로 모두 `TownLoadRegistry`처럼 상한 없는 캐시 풀(데몬 플랫폼 스레드)에 동네별 작업을 올리고, 호출 스레드가 전부 끝날 때까지 기다린다.
  - 지연은 제출부터 모든 동네 완료까지다.
  - 요청 버전은 측정 전에 한 번 관측해 두고 두 경로에 똑같이 넣었다. DB 로더 안의 read-view 버전 조회는 그대로 남아 있다.
- 로컬 캐시·레지스트리를 거치지 않는다. 반복마다 새 객체를 만든다.
  - 적재 직후 `hasOrder(POPULAR)`가 참인 경우(미리 만든 배열을 다시 쓴 경우)를 셌다: **0**.
  - Redis 미스는 곧바로 실패로 처리하게 했다: 실패 **0**.
- 범위 밖: Redis 미스 fallback, 발행 재시도, 서버 간 중복 방지, MGET/IN 배치.

**환경**

| 항목 | 값 |
|---|---|
| 하니스 | 컨테이너 `--cpus 2 --memory 2g`, cgroup `cpu.max 200000 100000` |
| 하니스 JVM | Temurin 21.0.11, G1, `-Xms1280m -Xmx1280m -XX:+AlwaysPreTouch -XX:MaxMetaspaceSize=256m` |
| DB 접속 | datasource URL·Hikari 10·p6spy OFF를 bench-app 환경에서 복사했다(값은 기록하지 않음). 목록 소스는 기본값 `TOWN_LAZY_SORT` |
| MySQL | 기존 `solply-bench-mysql` 8.0.46 (2 CPU/3G) |
| Redis | 측정 전용 `solply-bench-redis-town` 7.4.11, 1 CPU/256MiB, `--save '' --appendonly no`. 같은 `solply-bench-net`에 띄웠고 측정 뒤 삭제했다 |
| 클라이언트 | Lettuce 6.3.2(앱의 `LettuceConnectionFactory` 빈), Jackson 2.17.2 |
| 호스트 | macOS arm64, Docker 28.0.1, VM 11 vCPU / 3.8 GiB |

- 모든 컨테이너가 한 VM 안에서 도는 로컬 비교다. 원격 운영망의 네트워크 지연은 반영되지 않는다.
- bench-app은 하니스가 도는 동안 `docker pause` 했다가 `unpause` 했다.
  - 전후 상태 `true/false`, LB 헬스 200. 다른 컨테이너는 건드리지 않았다.

## 2. 데이터와 동등성 (`results/main2-20260924-230625/equivalence.txt`)

**데이터**
- 단일 동네: 302(101곳, 버전 25).
- 서울 18동네: 301~318, 합계 1,800곳. 302를 뺀 나머지는 모두 버전 22다.
  - 서울 201의 하위 동네 중 빈 픽스처 9001(장소 0, 버전 행 없음)은 실행안대로 뺐다.
- 동네당 payload: 27,950~28,506 B, 18개 합 506,914 B. 302는 28,506 B.
- 문자열 크기(302): 이름 1,215자, 썸네일 키 1,600자.

**동등성 검사** (측정 전에 동네마다 수행)
- 비교 대상: DB 적재, Redis 적재, DB 재적재.
- 비교 항목: 버전, 장소 수, 장소 집합, `PlaceEntry` 전 필드(record equals), `PlaceView` 전 필드, 인기순 배열의 원소와 순서.
- 교차 확인: SQL 장소 수와 버전 행.
- 서울 18동네는 병렬로 올린 경로에서도 DB와 Redis를 한 번 더 비교했다.
- 결과: **18/18 PASS, 병렬 비교 PASS**.

**DB가 측정 중 바뀌지 않았는지**
- 측정 전후로 버전 행과 `place_stats` 전 컬럼의 CRC 지문을 떴다: 둘 다 `versions=18:399:3725390745 place_stats=1800:401783567`.

## 3. 측정 방법

- 준비 구간(판정 제외): 경로마다 단일 동네 3,000회, 서울 300회를 돌렸다. 발행 경로도 같은 횟수다.
- 측정: 조건마다 3블록을 돌리고, 블록 안 순서는 DB→Redis / Redis→DB / DB→Redis로 교대했다.
  - 블록 크기: 단일 동네 300회, 서울 150회.
  - 블록 직전에 `System.gc()` + 0.5초 쉼(측정 창 밖).
- 서울 조건은 반복 사이에 30 ms를 쉰다.
  - 이 쉼은 지연 계측 밖이고, CPU·할당 창 안이다.
  - 단일 동네 조건은 쉼 없이 연속 반복한다.
- 계측 범위:
  - **앱 CPU**: 하니스 컨테이너 cgroup `usage_usec`. JVM 프로세스 전체라 적재 풀, Lettuce I/O 스레드, GC·JIT, Tomcat·Hikari 유휴 스레드가 모두 들어간다.
  - 같은 값을 스레드 그룹별(`ThreadMXBean`)로도 나눴다. 적재 풀(`town-bench-loader-*`), Lettuce(`lettuce*`), 그 밖의 Java 스레드로 나누고, 나머지는 JVM 내부(GC·JIT)다.
  - **할당**: `getTotalThreadAllocatedBytes()`, 모든 스레드 합이다.
  - **MySQL·Redis CPU**: 각 컨테이너 cgroup `cpu.stat`(VM의 `/sys/fs/cgroup`를 읽기 전용으로 마운트해 읽었다).
  - **전송 바이트**: MySQL `Bytes_sent`, Redis `total_net_output_bytes`.
- 블록 경계에서 계측 자체가 쓰는 비용(상태 조회 쿼리, INFO 2회)은 블록마다 수 ms로 고정이고, 블록 값에 섞여 있다.
- 유휴 블록(6.17초 × 2)의 배경 CPU:
  - 앱 34~37 ms, MySQL 67~68 ms, Redis 50~55 ms.
  - 벽시계 1 ms당 앱 약 6 µs, MySQL 11 µs, Redis 8~9 µs다.
  - 서울 반복 한 번의 벽시계는 약 35~40 ms라, 반복당 배경 CPU는 앱 ≤0.24 ms, MySQL 약 0.4 ms, Redis 약 0.33 ms가 섞인다.

## 4. 결과 — 적재 (유효 실행 `main2-20260924-230625`, 3블록 중앙값 [최소~최대])

표의 모든 값은 **완료 1회당**이다(단일 = 동네 1곳, 서울 = 18곳 전부).

| | 단일 DB | 단일 Redis | 서울18 DB | 서울18 Redis |
|---|---|---|---|---|
| 지연 p50 | 838 µs [813~872] | 237 µs [237~239] | 9.21 ms [9.12~9.70] | 3.13 ms [2.85~3.17] |
| 지연 p95 (참고, 판정 안 함) | 1,278 µs [1,044~1,408] | 484 µs [434~495] | 12.6 ms [12.6~13.9] | 4.77 ms [4.40~4.81] |
| 앱 CPU (cgroup, 프로세스 전체) | 496 µs [467~614] | 208 µs [206~324] | 26.0 ms [25.2~27.5] | 9.50 ms [9.33~9.94] |
| ├ 적재 풀 스레드 | 402 µs | 122 µs | 23.2 ms | 7.06 ms |
| └ Lettuce I/O 스레드 | 1 µs | **61 µs** | 0.06 ms | **1.57 ms** |
| 할당 (전 스레드) | 455 KiB | 144 KiB | 8,096 KiB | 2,541 KiB |
| GC (블록당) | 0 | 0 | Young 3회, 10~19 ms | 0 |
| MySQL CPU | 351 µs [349~387] | 3 µs (배경) | 21.3 ms [21.2~22.2] | 0.38 ms (배경) |
| Redis 서버 CPU | 4 µs (배경) | 31 µs [30~32] | 0.23 ms (배경) | 1.06 ms [1.03~1.12] |
| 전송 바이트 | MySQL→앱 14,195 B | Redis→앱 28,522 B | MySQL→앱 253,432 B | Redis→앱 507,107 B |
| 원격 호출 | MySQL 문장 8개 | GET 1회 | MySQL 문장 144개 | GET 18회 |

- 세 블록 모두 방향이 같다. Redis 경로가 지연(p50), 앱 CPU, 할당에서 모두 낮았다.
- 블록 간 범위도 겹치지 않았다.
- 전송 바이트는 반대로 Redis가 약 2배 많다. MySQL 이진 결과보다 필드 이름을 그대로 둔 JSON이 크기 때문이다.
- MySQL 문장 8개는 `Questions` 기준이다. 트랜잭션 설정·시작·버전 조회·행 조회·커밋 등이 포함되며, 서울은 8 × 18 = 144개다.
- 서울 조건에서 동네 1곳당 비용은 단일 동네 조건보다 크다.
  - 앱 CPU로 보면 DB 1.45 ms vs 0.50 ms, Redis 0.53 ms vs 0.21 ms다.
  - 18개 작업이 2 CPU와 Hikari 10을 나눠 쓰기 때문으로 보이지만, 원인은 분해하지 않았다.
- 스로틀·스왑·유효성:
  - 측정 블록의 스로틀 `nr_throttled` 0.
  - 측정 블록의 VM 스왑 입출력 0.
  - 실행 결과 `RUN VALID`.

## 5. 결과 — Redis 발행 비용 (별도, DB 읽기 섞지 않음)

- 미리 확보한 `TownPlaces`로 DTO를 만들고 JSON 직렬화까지 한 것이 "직렬화"다.
- "발행"은 직렬화 + SET이다. 키 공간을 분리해(`pub:` 접두어) 읽기 키는 건드리지 않았다.

| | 단일 직렬화 | 단일 발행 | 서울18 직렬화 | 서울18 발행 |
|---|---|---|---|---|
| 지연 p50 | 137 µs | 242 µs | 1.56 ms | 2.53 ms |
| 앱 CPU (cgroup) | 145 µs [140~195] | 227 µs [207~246] | 6.87 ms [6.31~7.09] | 8.39 ms [8.28~9.02] |
| └ Lettuce I/O | 1 µs | 57 µs | 0.06 ms | 1.39 ms |
| 할당 | 105 KiB | 106 KiB | 1,844 KiB | 1,867 KiB |
| Redis 서버 CPU | — | 35 µs | — | 0.99 ms (배경 약 0.3 포함) |
| 앱→Redis 바이트 | — | 28,558 B | — | 507,850 B |

- Redis 메모리:
  - 키당 `MEMORY USAGE` 28,736 B(302, 문자열 길이 28,506 B).
  - 18키를 올린 뒤 `used_memory` 증가분은 575,136 B, 키당 약 31.9 KB다.
- 이 표는 발행 1회의 비용이다. 적재 비용과 더하거나 빼서 "몇 번 읽으면 이득"을 계산하지는 않았다.

## 6. 무효·보조 실행

- `results/smoke-20260924-230247/`: 하니스 동작 확인용이다(소량 반복). 판정에 쓰지 않는다.
  - 그 전의 smoke 1회는 `MEMORY USAGE`를 Spring의 범용 `execute`로 부르다 Lettuce 출력 타입 오류가 나서, 결과 없이 삭제했다.
  - 이 값은 run.sh에서 `redis-cli`로 읽도록 옮겼다.
  - 같은 smoke에서 env 복사 정규식이 `LOGGING_LEVEL_P6SPY`를 놓친 것을 발견해 고쳤다. 숫자 6이 문자 클래스 밖이었다. 유효 실행은 P6SPY OFF가 들어간 뒤의 것이다.
- `results/main-20260924-230412/`: **무효**(`INVALID.txt`).
  - 첫째, 준비 500/100회로는 부족했다. 단일 동네 DB 블록 CPU가 1,628→1,205→824 µs로 계속 떨어졌다(JIT 진행 중).
  - 둘째, 서울 조건을 쉼 없이 연속 반복해서 2 CPU 쿼터를 거의 매 주기 소진했다(`nr_throttled` 13~15). p95 약 60 ms가 스로틀 정지였다.
  - 방향(Redis가 지연·CPU·할당 모두 낮음)은 같았지만, 수치는 인용하지 않는다.
  - 다만 이 실행의 서울 블록은 앱 CPU가 유효 실행보다 낮았다(DB 18.8~21.0 ms, Redis 4.7~6.2 ms). 연속 반복과 30 ms 쉼 반복 사이에서 반복당 CPU가 달라지는 이유는 확인하지 않았다.
  - 따라서 서울 조건의 CPU 절대값은 반복 간격에 따라 움직일 수 있고, 두 경로 사이의 비율도 연속 반복 3.3~4.4배, 쉼 반복 2.6~2.9배로 달랐다.

## 7. 이 측정으로 말할 수 없는 것

- 원격 Redis·원격 MySQL의 네트워크 지연. 같은 VM 안의 브리지 네트워크다.
- 전체 API 응답시간과 처리량. HTTP·인증·응답 직렬화는 범위 밖이다.
- Redis 미스 fallback, 발행 재시도·중복 방지, 버전 불일치 처리의 비용과 정합성. 구현하지 않았다.
- MGET·IN 배치 같은 묶음 최적화의 효과.
- p95. 기록만 했고 재현성을 검증하지 않았다.
- 서울 조건 CPU의 반복 간격 의존성(6절)의 원인.

## 8. 산출물 경로

- 하니스: `load-test/campaigns/2026-09-24_redis-town-load/bench/`
  - `build-harness.sh`, `run.sh`, `table.py`, `src/org/sopt/solply_server/domain/place/cache/town/TownLoadBench.java`
- 유효 결과: `results/main2-20260924-230625/`

  | 파일 | 내용 |
  |---|---|
  | `blocks.csv` | 블록별 전 지표 |
  | `blocks-table.txt` | 사람이 읽는 표 |
  | `latencies.csv` | 측정 반복별 지연 |
  | `equivalence.txt` | 동등성 결과 |
  | `env.txt` | 환경 |
  | `run-env.txt` | git·jar·이미지·스왑·앱 상태 |
  | `redis-memory-end.txt` | 키별 MEMORY USAGE |
  | `gc.log` | GC 로그 |
  | `harness-stdout.log` | 하니스 표준 출력 |

- 재현:

  ```
  BUILD_DIR=<dir> bench/build-harness.sh
  CLASSES=<dir>/classes bench/run.sh <label>
  ```

- `load-test/`는 이 워크트리의 `.gitignore:126`(`/load-test/`)에 걸려 있어 git 추적 대상이 아니다.
