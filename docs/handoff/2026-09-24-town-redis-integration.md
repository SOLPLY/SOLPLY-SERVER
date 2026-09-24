# 보고 — 동네 다중 버전 Redis 제품 통합

실행안: `docs/superpowers/plans/2026-09-24-town-redis-integration.md`.
작성 2026-09-24, Claude Opus 5.5. 커밋·푸시·배포 없음. 기존 dirty 변경은 건드리지 않았다(아래 목록의 파일만 수정·추가). 새 성능 측정은 하지 않았다.

## 1. 핵심 동작

**조회 (`TownLoadRegistry`, `TownPlaceListService`)**
- 확보 순서는 로컬 → Redis → DB다. 같은 `(동네, 번호)`는 한 비행이 셋을 모두 맡고, 대기자는 사본(`copy()`)만 받는다. 요청 예산 1초·취소 격리·실패 즉시 전달은 그대로다.
- 로컬 hit은 Redis도 DB도 보지 않는다. 보완 대기인 키일 때만 로컬 객체를 비동기로 다시 싣는다(메모리 조회 한 번).
- 로컬 miss는 Redis를 먼저 본다. hit이면 검증 뒤 새 불변 `TownPlaces`로 복원해 로컬에 올린다. 과거 번호여도 된다. 정렬 배열은 싣지 않고 기존처럼 요청받은 축만 그 자리에서 만든다.
- Redis 결과는 `Fetch.Hit / Miss / Unavailable` 셋으로 나뉜다. 접속 실패·시간 초과·깨진 내용(스키마·키 불일치, 필드 누락, 중복 id)은 모두 `Unavailable`이다.
- Redis에 없을 때 DB로 가는 조건은 호출부가 고른다.
  - 첫 페이지(`LOAD_CURRENT`): 방금 관측한 번호라 곧바로 RR 로더를 부른다.
  - 다음 페이지(`IF_LATEST`): 그 동네의 지금 번호를 먼저 관측하고, 커서의 번호가 최신일 때만 로더를 부른다. 과거 번호 때문에 원본을 읽지 않는다.
- 로더가 읽은 번호가 요청한 번호와 다르면 그 객체는 **실제 번호로만** 로컬·Redis에 올리고 요청에는 쓰지 않는다. 그 전에 로컬과 Redis(직전이 정상 miss였을 때만)를 한 번 더 본다.
- 다음 페이지의 결과:
  - 요청한 번호를 확보하면 이어 간다. 이미 로컬에 있던 동네는 다시 확보하지 않는다.
  - Redis 정상 miss이고 DB도 그 번호를 만들 수 없으면 `EXPIRED_PLACE_CURSOR`.
  - Redis를 확인하지 못했으면 재시도 가능 오류(`PLACE_SNAPSHOT_SYNCING`, 계측 사유 `shared_unavailable`). 만료로 바꾸지 않는다.
- 첫 페이지는 기존대로 관측 번호를 전부 확보하지 못하면 예산 안에서 다시 관측한다(재관측 한도 2).

**발행 (`TownRedisPublisher`, `TownCommitPublisher`)**
- 형식: 무압축 JSON schema 1. 키: `{app.env-prefix}:place-list:town:s1:{townId}:v{version}`.
- 저장은 `SET NX PX`(한 명령). 이미 있으면 내용도 남은 보관 기간도 바꾸지 않는다.
- 같은 키는 **제출 전에** 한 쪽만 소유한다(비동기 발행·보완·커밋 뒤 발행이 같은 소유 집합을 쓴다). 소유는 작업이 끝나거나 제출이 거절되면 풀린다.
- 실패하면 곧바로 1회 재시도하고, 그래도 실패하면 키만 보완 대기에 둔다. 대기는 로컬 보관 기간(65분)과 개수 상한(10,000)으로 사라진다. 보완은 그 번호의 로컬 객체가 있을 때 다음 로컬 hit이 올리며 DB를 읽지 않는다.
- DB 폴백의 승자 비행이 확보한 객체 하나를 비동기로 싣는다. 대기자별 발행은 없다.
- 커밋 뒤 발행: `TownVersionService`의 네 입구(지문 비교·정렬 키·동네 지목·전체 배치)가 쓰기 트랜잭션에 동네 id를 모은다. 커밋 뒤 비동기 작업 하나가 새 RR 트랜잭션으로 번호와 원본을 함께 읽어 **실제로 읽은 번호**로 싣는다. 롤백이면 아무것도 하지 않는다. 제출 실패는 로그만 남기고 쓰기를 실패시키지 않는다. 전체 배치는 `SELECT DISTINCT town_id FROM place_stats` 전부가 대상이다.
- 분산 락·서버 간 중복 방지·영속 outbox·새 작업 시스템은 두지 않았다. 프로세스 종료로 발행이 사라지면 다음 조회의 DB 폴백이 싣는다.

**연결 (`RedisTownSnapshotStore`, `TownSnapshotStoreConfig`)**
- 접속 대상은 기존 `spring.data.redis.*`를 재사용한다. 커넥션 팩토리는 이 저장소 전용으로 따로 만들고 빈으로 노출하지 않는다(`RedisConnectionFactory` 빈이 둘이 되면 기존 주입이 모호해진다). 전역 Redis 설정·maxmemory는 건드리지 않는다.
- connect·command timeout 200ms, 끊긴 동안 명령 즉시 거절(`REJECT_COMMANDS`).
- `enabled=false`면 I/O 없이 늘 "없음"으로 답하는 저장소가 선다. 동작은 공유 사본이 없던 구조와 같다.

## 2. 초기 설정

| 키 (`solply.place-list-town-cache.redis.*`) | 값 | 비고 |
|---|---|---|
| `enabled` | true | test 프로필·테스트 classpath는 false |
| `connect-timeout` / `command-timeout` | 200ms / 200ms | Redis가 응답하지 않으면 로컬 miss 한 건이 최대 약 400ms를 쓰고 DB로 간다 |
| `payload-ttl` | 65m | 로컬 보관과 같은 초기 설정. 최적값 검증 아님 |
| `publish-retries` | 1 | |
| `pending-max-entries` | 10,000 | 키만 보관 |

- `app.env-prefix`: `application.yml`은 `local`, 이번에 `application-dev.yml`에 `dev`, `application-prod.yml`에 `prod`를 추가했다. 기존 코드에서 이 값을 읽는 곳은 없었다. 배포 환경변수 `APP_ENV_PREFIX`가 있으면 그것이 우선한다.
- 로컬 보관 65분·추정 상한 64MiB는 그대로다.
- 계측 추가: `solply.town.redis.lookups{result=hit|miss|unavailable}`, `solply.town.redis.publishes{outcome=stored|existing|failed}`, `solply.place.list.budget.exceeded{reason=shared_unavailable}`. `solply.town.cache.loads`는 이제 **DB 적재만** 센다(Redis 복원은 적재가 아니다).

## 3. 벤치 codec과의 차이

| | 벤치(`TownLoadBench`) | 제품(`TownPayloadCodec`·`RedisTownSnapshotStore`) |
|---|---|---|
| JSON 모양 | schema·townId·version·places[12필드] | **같다**(필드 이름·순서 같음, 무압축) |
| 역직렬화 검사 | townId·version만 | + schema=1, places·원소 non-null, 필드 누락 실패, 원시 필드 null 실패, 모르는 필드 실패, placeId 중복 실패 |
| 표시값 없음 | NPE | 표시 필드를 null로 싣는다 |
| 키 | `bench:town:{t}:v{v}` | `{env}:place-list:town:s1:{t}:v{v}` |
| 쓰기 | `SET` (TTL 없음) | `SET NX PX 65m` |
| 연결 | 앱의 공용 `LettuceConnectionFactory` | 전용 팩토리, timeout 200ms |
| 복원 | `TownPlaces.objectsOnly` | 같다 |

- 벤치 결과를 최종 코드에 적용할 수 있는 범위: "같은 번호 한 동네를 로컬 VM 안에서 DB 로더 대신 Redis GET+역직렬화로 복원할 때의 상대 비용"까지다. 제품 경로는 여기에 로컬 조회·레지스트리 비행·실행기 전환·검사가 더해지고, 원격 망 지연은 여전히 측정 밖이다. 벤치 수치를 API 지연·처리량으로 인용하지 않는다.

## 4. 변경 파일

**main (추가)** — `domain/place/cache/town/`: `TownPayloadCodec`, `TownSnapshotStore`, `RedisTownSnapshotStore`, `TownRedisPublisher`, `TownCommitPublisher`, `TownLoad` / `domain/place/config/TownSnapshotStoreConfig`

**main (수정)** — `TownLoadRegistry`, `TownPlaceListService`(생성자에서 `TownPlacesCache` 제거), `TownVersionService`, `TownVersionRepository`(`townIdsWithPlaces`), `PlaceListTownCacheProperties`(중첩 `redis`), `PlaceListMeters`, `application.yml`, `application-dev.yml`, `application-prod.yml`

**test (추가)** — `cache/town/`: `FakeTownSnapshotStore`, `DirectExecutorService`, `TownPayloadCodecTest`, `TownSharedSnapshotFlowTest`, `TownRedisPublisherTest`, `TownRedisIntegrationIT` / `src/test/resources/config/application.yml`(테스트 전체에서 Redis 공유 사본 끔)

**test (수정)** — `TownLoadRegistryTest`, `TownPlaceListServiceCursorTest`(실제 레지스트리 + 메모리 저장소로 다시 씀), `TownPlacesLazyOrderTest`, `PlaceListMetersTest`, `PlaceStatsBatchProcessorIT`·`BookmarkCountDeltaProcessorIT`(`@MockBean TownCommitPublisher`), `application-test.yml`

## 5. 검증

구현을 마친 뒤 모아서 돌렸다. Docker VM 3.8 GiB, 벤치 컨테이너 3개(약 1.7 GiB)는 멈추지 않고 그대로 둔 채 실행했다. 무관한 컨테이너·볼륨은 건드리지 않았다.

| 명령 | 결과 |
|---|---|
| `./gradlew test --tests '…cache.town.*' --tests '…metrics.*'` (1차) | 182건 중 10건 실패 — `TownRedisIntegrationIT` 전부. 원인: `MySqlContainerSupport`의 `@DynamicPropertySource`로 끈 값을 하위 IT가 다시 켤 수 없었다(상위 메서드가 나중에 등록돼 이긴다). 수정: 끄는 값을 `src/test/resources/config/application.yml`로 옮겨 IT의 동적 속성이 덮게 했다. |
| 같은 명령 (2차, 발행 소유 수정 뒤) | **188건 통과, 실패 0** (`TownRedisIntegrationIT` 10건 포함) |
| `./gradlew test` (전체 1차) | 829건 중 51건 실패 — `PlaceStatsBatchProcessorIT` 39, `BookmarkCountDeltaProcessorIT` 12. 원인: 두 `@DataJpaTest` 슬라이스가 `TownVersionService`를 직접 import하는데 새 의존 `TownCommitPublisher`가 슬라이스에 없어 컨텍스트 기동 실패. 수정: 두 클래스에 `@MockBean TownCommitPublisher`. 두 클래스만 다시 돌려 통과 확인. |
| `./gradlew test` (전체 최종) | **93 클래스, 829건 통과, 실패·오류·건너뜀 0** (3분 35초) |
| `./gradlew bootJar` (최종) | 성공 |

**무엇을 어디서 확인했나**
- 실제 Redis(`redis:7.4-alpine`, requirepass, maxmemory 128mb·allkeys-lru) — `TownRedisIntegrationIT`
  - 전체 필드 왕복·인기순 동일·키 형식, 스키마/키 불일치·깨진 JSON → 확인 불가, 정상 miss.
  - SET NX가 기존 내용을 지키고 남은 보관 기간을 늘리지 않음(1.1초 뒤 재발행 → PTTL이 1초 이상 줄어 있음). 보관 기간 800ms → 1.2초 뒤 miss.
  - 닿지 않는 포트 → 1초 안에 확인 불가, 싣기는 예외.
  - 커밋 뒤 발행: 커밋에서만 새 번호, 영향 동네만, 롤백이면 없음, 전체 배치는 장소가 있는 동네 전부, 늦게 돈 작업은 실제로 읽은 번호(v2)만 싣고 v1은 싣지 않음.
  - 끝에서 끝: 첫 페이지(v1) → 정렬 키 변경 + 번호 올림(v2) → 로컬 비움 → v1 커서의 다음 페이지와 새 첫 페이지 모두 DB 적재 0으로 올바른 순서.
- 제품 service/registry(실제 레지스트리 + 메모리 저장소, DB만 대역) — `TownPlaceListServiceCursorTest` 20건, `TownSharedSnapshotFlowTest` 7건
  - 로컬 hit 무I/O, Redis 최신·과거 hit 무DB, Redis miss·장애 시 최신은 DB 폴백, 과거 miss는 만료 / 장애·깨짐은 재시도(그 뒤 같은 커서로 성공), 로더 번호 경쟁(실제 번호로만 저장, 경쟁 중 원래 번호가 Redis에 오면 그것을 씀), 같은 키 동시 5요청 → 조회·적재·발행 각 1회, 시간 초과 요청이 공유 확보를 취소하지 않음, 예산이 끝난 요청은 아무것도 시작하지 않음.
  - 다른 키 병렬은 기존 `TownLoadRegistryTest`(여섯 동네 동시 시작)가 그대로 통과.
- 발행 소유 — `TownRedisPublisherTest` 6건. 실행기를 붙잡아 둔 채: 같은 키 비동기 발행 3회 → 제출 1, 비동기 소유 중 커밋 뒤 발행은 싣지 않음, 보완 대기 키에 로컬 hit 5회·동시 8스레드 → 제출 1, 보완 끝난 뒤 hit은 제출 0, 제출 거절 시 소유 해제 + 대기, 재시도 1회 뒤 대기로 멈춤. 로컬 hit 보완의 DB 읽기 0은 `TownSharedSnapshotFlowTest`에서 확인.
- codec — `TownPayloadCodecTest` 9건(벤치와 같은 필드 이름·순서, 누락·null 원시·중복·빈 원소·깨진 JSON 거부).

## 6. 남은 사항

- 벤치 준비 초기화(`BenchPrepResetService`)는 로컬 캐시만 비우고 Redis는 비우지 않는다. DB 적재 경로를 재는 비교에서는 `solply.place-list-town-cache.redis.enabled=false`로 띄워야 한다.
- 다음 페이지에서 로컬·Redis 모두 없는 동네가 여럿이면 번호 관측이 동네마다 한 번씩 돈다(예전에는 없는 동네를 모아 한 번). 결과는 같고 문장 수만 늘어난다.
- Redis가 블랙홀처럼 응답하지 않으면 로컬 miss마다 timeout(설정상 connect 200 + command 200ms, 블랙홀 상황은 측정하지 않음)을 기다린 뒤 DB로 간다. 차단기는 두지 않았다(실행안 범위 밖).
- 운영 Redis의 maxmemory 정책은 확인하지 않았다. `noeviction`에서 가득 차면 SET이 실패해 보완 대기 → 다음 조회 DB 폴백으로 흐른다. 인증 토큰과 같은 인스턴스라는 점은 그대로다.
- 운영 인프라 배포·환경변수 추가는 범위 밖이다.

메인 확인: 최종 XML을 직접 집계해 93클래스·829건·실패/오류/스킵 0을 확인하고 제품 조회·발행 경로와 대조했다. [로그와 XML 집계](2026-09-24-town-redis-integration-evidence/)를 별도로 보관한다. 관련 1차와 전체 1차 실패 로그도 포함하며, 실패 실행의 XML 전체를 보관한 것은 아니다.
