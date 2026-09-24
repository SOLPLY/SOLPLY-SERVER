> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# 검증 기록: 장소 목록 동네 버전·동네 캐시

작성일: 2026-09-21. 대상 형상: `develop@64a97e08274303199b33244ba579adde22e4437d` 위의 작업 트리.
정본 요구사항: [설계](../design/2026-09-21-place-list-town-cache.md),
[메인 계약 검토](../handoff/2026-09-21-main-contract-review.md).
구현 계획: `docs/superpowers/plans/2026-09-21-place-list-town-cache.md` (리포지토리 ignored).

**이 문서는 기능 계약의 검증 기록이다.** 성능 우승 판정, 부하 측정, 캐시 예산 확정은 여기 없다.

후속 상태: 아래 707개 결과와 `DB_DIRECT` 미연결 설명은 이 단계의 기록이다.
이후 [비교 경로 통합 검증](2026-09-21-comparison-integration.md)에서 `DB_DIRECT` HTTP 연결과
계측·bench 준비 경로를 추가했고, 최종 clean build는 **752개, 실패/오류/skip 0**이다.
아래의 성능 미측정 문구도 이 단계에 한정하며, 이후 캠페인 결과와 혼동하지 않는다.

## 1. 실행 환경과 명령

| 항목 | 값 |
|---|---|
| 빌드 명령 | `./gradlew clean build` (작업 트리 루트), gradle exit code **0** |
| Gradle | 8.14.2 |
| 테스트·컴파일 JDK | Eclipse Temurin 21.0.8+9-LTS (`java.toolchain = 21`) |
| Gradle 런처 JVM | Homebrew OpenJDK 25.0.2 — 툴체인이 21을 잡아 컴파일·테스트는 21에서 돈다 |
| MySQL | Testcontainers `mysql:8.0`, 싱글턴 컨테이너 + Flyway |
| Docker | Docker Desktop, 서버 28.0.1 |

**Docker daemon은 작업 시작 시점에 연결되지 않았다.** 첫 확인에서
`Cannot connect to the Docker daemon at unix:///Users/mkyu/.docker/run/docker.sock`가 났고,
Docker Desktop을 띄운 뒤 정상화됐다. MySQL 통합 검증을 H2나 skip으로 대체하지 않았다.

## 2. 결과

`./gradlew clean build` — **BUILD SUCCESSFUL**, 프로세스 exit code 0.

| 지표 | 값 |
|---|---|
| 전체 테스트 | 707 |
| 실패 | 0 |
| 오류 | 0 |
| skip | 0 |

새로 추가한 테스트 클래스:

| 클래스 | 종류 | 테스트 수 |
|---|---|---|
| `TownVersionsTest` | 단위 | 7 |
| `TownPlacesTest` | 단위 | 10 |
| `TownListReaderTest` | 단위 | 7 |
| `TownLoadRegistryTest` | 단위 | 9 |
| `TownPlacesCacheTest` | 단위 | 7 |
| `TownVersionBumpIT` | MySQL | 17 |
| `TownCacheListFlowIT` | MySQL | 12 |
| `TownCacheConcurrencyIT` | MySQL | 10 |
| `TownCacheBudgetIT` | MySQL | 5 |

이 집계에는 별도 작업자가 설치한 `TownDbDirectReaderIT`(9개)가 포함돼 있다 —
DB 직접 조회 리더는 내 소유가 아니며, 그 계약의 검증 기록은
[별도 문서](2026-09-21-db-direct-reader.md)에 있다.

원자료:
[빌드 전체 로그](../../../verification/logs/2026-09-21-town-cache-build.log)(`./gradlew clean build`, gradle exit code 0),
[클래스별 XML 요약](../../../verification/logs/2026-09-21-town-cache-tests.txt),
[DB 리더 IT 로그](../../../verification/logs/2026-09-21-db-direct-reader-it.log)·[집계](../../../verification/logs/2026-09-21-db-direct-reader-tests.txt).

`logs/2026-09-21-town-cache-build-DISCARDED-overlapped.log`는 **근거에서 제외한다.** 그 실행은
DB 리더 파일 설치와 겹쳐, 설치 중간 상태의 Spring 컨텍스트가 깨지며 무관한 IT 43개가 함께
죽었다. 설치가 끝난 뒤 같은 IT들을 다시 돌려 통과를 확인했고 위 최종 실행으로 대체했다.

## 3. 요구사항별 구현 위치와 테스트

### 3.1 자동 bump 분류 — 지문 비교

어느 동네를 올릴지는 **쓰기 전후의 `place_stats` 지문**이 정한다.
지문은 `(town_id, tag_bitmask)` 둘뿐이다 — 탐색의 대상 범위와 필터 통과 여부를 정하는 값.

| 변경 | 쓰기 전 | 쓰기 후 | 올리는 동네 |
|---|---|---|---|
| 생성 | 없음 | A | A |
| 삭제·비활성 | A | 없음 | A |
| 동네 이동 | A | B | A와 B |
| 태그 재지정 | A/마스크1 | A/마스크2 | A |
| **이름·썸네일·소개·주소·좌표만** | A/마스크1 | A/마스크1 | **없음** |

호출부가 변경 종류를 분류하지 않는다. 분류를 손으로 하면 갈래가 하나 새는 순간 그 동네의
캐시가 조용히 틀린다.

| 요구 | 구현 | 테스트 |
|---|---|---|
| 동네마다 번호 하나 | `V48__place_list_town_versions.sql`, `TownVersionRepository` | `TownVersionBumpIT#행이_없던_동네는_0이고_첫_bump가_행을_만든다` |
| 지목한 동네만 오른다 | `TownVersionRepository#bump` | `TownVersionBumpIT#bump는_지목한_동네만_올린다` |
| 생성 | `AdminPlaceService#createPlace` | `TownVersionBumpIT#장소_생성은_그_동네의_번호를_올린다` |
| 삭제 | `AdminPlaceService#deletePlace` | `TownVersionBumpIT#장소_삭제는_그_동네의_번호를_올린다` |
| **이동은 양쪽** | `TownVersionService#markChangedIfSearchAffecting` | `TownVersionBumpIT#동네_이동은_양쪽_번호를_올린다` |
| **이름만 바꾼 실제 수정은 bump 없음** | 같은 자리 | `TownVersionBumpIT#이름만_바꾼_실제_수정은_번호를_올리지_않는다` |
| 소개·좌표만도 bump 없음 | 같은 자리 | `TownVersionBumpIT#소개와_좌표만_바꾼_수정도_번호를_올리지_않는다` |
| 태그를 갈면 bump | 같은 자리 | `TownVersionBumpIT#태그를_바꾼_수정은_번호를_올린다` |
| 운영자 체크에 의존하지 않는다 | 같은 자리 | `TownVersionBumpIT#목록_재시작을_고르지_않아도_생성은_번호를_올린다` |
| 지문에 재료를 넘기고 잠금이 먼저 | `AdminPlaceService#updatePlace` | `AdminPlaceServiceUpdateRoutingTest#지문을_읽기_전에_대상_행을_잠근다` |
| 정렬 키값만 바뀐 경로 | `TownVersionService#markSortKeysChanged` | `BookmarkCountDeltaProcessorIT`(슬라이스 회귀) |

세 표시-전용 테스트는 **실제 `AdminPlaceFacade.updatePlace`를 부른다.** JDBC로 표시 칸만 직접
고쳐 놓고 "번호가 그대로다"를 단언하면 쓰기 경로를 지나지도 않은 채 언제나 통과한다 — 무조건
bump하는 구현도 그린이 된다.

### 3.2 동시 쓰기 — 실제 출발지

`AdminPlaceService#updatePlace`가 대상 행을 `PESSIMISTIC_WRITE`로 잠근 뒤 지문을 읽는다.
잠금 순서는 **언제나 `places` → `place_stats`**다.

**잠금만으로는 부족했다.** 쓰기 트랜잭션은 이미 REPEATABLE READ 스냅샷을 잡은 뒤라, 평범한
SELECT는 트랜잭션이 시작될 때의 `town_id`를 돌려준다 — 그 사이 다른 writer가 장소를 B로 옮기고
커밋했어도 A로 보인다. 그래서 지문 SQL에 **`FOR UPDATE`**를 붙였다. 잠금 읽기는 최신 커밋을 보며,
동시에 같은 행을 겨눈 다른 writer를 줄 세운다.

**테스트:** `TownVersionBumpIT#동시_이동에서_나중_writer는_실제_출발지를_올린다`.

별도 연결이 `FOR UPDATE`로 장소 행을 붙잡은 채 A→B로 옮기고 커밋한다. 잠금이 풀린 뒤 어드민
수정이 읽는 출발지가 A인지 B인지를 본다.

**순서를 시간으로 추정하지 않는다.** 가르는 것은 잠금 획득 시점이 아니라 **어드민 트랜잭션이
자기 스냅샷을 잡은 시점**이다. 그 트랜잭션의 첫 읽기(`AdminEntityLoader#getPlaceWithTown`)를
스파이로 가로채 그것이 실제로 끝난 것을 신호로 받고, 선행 writer는 그 **뒤에** 옮기고 커밋한다.
`Thread.sleep`으로 "이쯤이면 걸렸겠지"를 쓰면 느린 머신에서 순서가 뒤집혀 깨진 구현도 통과한다.

**음성 대조를 실제로 돌렸다.** `FINGERPRINT_SQL`에서 `FOR UPDATE`를 빼면 이 테스트 하나만
실패하고(`17 tests completed, 1 failed`), 되돌리면 다시 통과한다. 테스트가 그 한 줄을 실제로
지키고 있다는 증거다.

**검증 범위를 과장하지 않는다.** 선행 writer는 어드민 경로가 아니라 **번호를 올리지 않는 날것의
SQL**이다. 그래서 이 테스트가 증명하는 것은 "두 어드민 요청이 경쟁해도 괜찮다"가 아니라
**"뒤에 오는 writer가 자기가 먼저 읽어 둔 값이 아니라 지금의 출발지를 본다"** 하나다.

빠뜨렸을 때의 실제 피해: A→B가 커밋된 뒤 B를 조회해 커서를 받아 둔 사용자는, 뒤이은 B→C가 B를
올리지 않으면 **이미 떠난 장소를 계속 보게 되고 번호가 그대로라 만료도 되지 않는다.**

### 3.3 한 read view

| 요구 | 구현 | 테스트 |
|---|---|---|
| 요청의 번호 관측이 한 read view | `TownVersionRepository#readInCurrentTransaction` (IN 한 문장) | `TownVersionBumpIT#여러_동네의_번호를_한_read_view에서_읽는다` |
| **적재의 번호와 원본이 같은 시점** | `TownSourceLoader#load` — `REQUIRES_NEW` + `REPEATABLE_READ` 한 트랜잭션 | `TownCacheConcurrencyIT#번호와_원본_사이에_커밋돼도_같은_시점을_돌려준다` |
| 관측보다 뒤의 데이터를 옛 번호로 표기 금지 | `TownPlaceListService#settle` | `TownCacheConcurrencyIT#적재_도중_번호가_오르면_첫_페이지는_다시_관측한다` |
| 첫 페이지는 제한 재관측 | `PlaceListTownCacheProperties#firstPageReobserveLimit`(기본 2) | 같은 테스트, `#재관측이_이미_적재된_새_번호를_다시_읽지_않는다` |
| 커서 요청은 만료로 끊는다 | 같은 자리 | `TownCacheConcurrencyIT#적재_도중_번호가_오르면_커서_요청은_만료다` |
| 이동 전/후를 섞어 중복하지 않는다 | 두 동네를 같은 시점으로 확보 | `TownCacheListFlowIT#두_동네에_걸친_탐색에서_이동한_장소가_중복되지_않는다` |

읽기 순서를 가로채는 방식으로 경쟁을 **결정적으로** 만들었다 — 번호를 읽은 직후 별도 연결이
장소를 하나 더 심고 번호를 올리고 커밋하게 한 뒤, 그 다음에 일어나는 원본 읽기가 새 행을
보지 않는지 본다.

### 3.4 커서 — 범위와 두 좌표계

| 요구 | 구현 | 테스트 |
|---|---|---|
| **v7 전역 토큰은 그대로 통한다** | `PlaceListCursor#encode`/`#decode`가 두 포맷을 든다 | `PlaceListCursorTest#v7_전역_토큰은_그대로_통한다` (encode 결과까지 바이트 비교) |
| 동네 경로만 v8 | 같은 자리 | `PlaceListCursorTest#포맷과_범위_표현이_어긋나면_거부한다` |
| 번호가 오르면 만료 | `TownPlaceListService#attempt` | `TownCacheListFlowIT#번호가_오른_뒤_온_커서는_만료다` |
| **leaf 범위 변화도 만료** | 범위 표현이 동네 집합을 담는다 | `TownCacheListFlowIT#하위_동네가_활성화되면_옛_커서는_만료다`, `TownVersionsTest#번호가_같아도_동네가_늘면_다른_표현이다` |
| 두 좌표계가 섞이지 않는다 | `T`/`G` 머리글자 + 포맷 번호 | `PlaceListCursorTest#전역_표현과_동네_표현은_섞이지_않는다` |
| **확인 후 커밋은 기존 참조로 응답** | 확보한 `TownPlaces` 참조가 불변 | `TownCacheConcurrencyIT#확보_뒤_커밋돼도_확보한_참조로_응답한다` |

거리순 토큰을 일괄 만료시키지 않는 것이 요점이다. 동네 경로에 새 포맷이 필요해졌다는 이유로
범위 밖 경로의 진행 중인 스크롤을 끊지 않는다.

### 3.5 상주 캐시 — 동네마다 한 벌

| 요구 | 구현 | 테스트 |
|---|---|---|
| **구버전 보관 없음** (키가 townId 하나) | `TownPlacesCache` | `TownPlacesCacheTest#동네마다_한_벌만_상주한다` |
| 원하는 번호일 때만 준다 | `TownPlacesCache#get(townId, version)` | `TownPlacesCacheTest#원하는_번호일_때만_돌려준다` |
| **늦은 옛 적재가 새 항목을 덮지 못한다** | `#publish`의 `merge` 단조 가드 | `TownPlacesCacheTest#낡은_번호의_게시는_거절된다`, `TownLoadRegistryTest#늦게_끝난_옛_적재가_새_번호의_항목을_덮지_않는다` |
| **빈 동네도 상한에 센다** | weigher `max(1, placeCount)` | `TownPlacesCacheTest#빈_동네도_상한에_센다` |
| 상한은 보관 비용 | 같은 자리 | `TownPlacesCacheTest#장소_수_상한을_넘으면_일부가_빠진다` |
| 축출은 만료가 아니다 | 같은 번호로 재적재 | `TownPlacesCacheTest#빠진_항목은_같은_번호로_다시_채울_수_있다`, `TownCacheListFlowIT#축출된_항목은_같은_번호로_다시_적재된다` |
| 인스턴스 이동 | 캐시를 비운 인스턴스가 곧 새 인스턴스 | `TownCacheListFlowIT#캐시를_비워도_같은_커서로_이어간다` |

요청이 옛 번호를 계속 보는 근거는 저장소에 그 번호가 남아 있어서가 아니라 **이미 참조를
잡았기 때문**이다. 그래서 상주는 한 벌이면 족하다.

### 3.6 공유 적재

| 요구 | 구현 | 테스트 |
|---|---|---|
| 동일 (동네, 번호) single-flight | `TownLoadRegistry#acquire` | `TownLoadRegistryTest#같은_키의_동시_요청은_적재를_한_번만_한다`, `TownCacheConcurrencyIT#동시_요청_다섯이_적재를_한_번만_한다` |
| 한 대기자의 timeout/cancel이 공유 작업을 취소하지 않는다 | `flight.copy()`만 내보낸다 | `TownLoadRegistryTest#한_대기자가_끊어도_공유_적재는_계속_돈다`, `TownCacheConcurrencyIT#예산을_넘겨_끊긴_요청도_공유_적재를_취소하지_않는다` |
| 실패는 그 flight에 즉시 전파 | `#settle` + `awaitAll`의 fail-fast | `TownLoadRegistryTest#적재_실패는_대기자에게_즉시_간다`, `TownCacheConcurrencyIT#적재_실패는_예산을_다_쓰기_전에_끊는다` |
| **등록/실행 순서에 기대지 않는다** | `putIfAbsent`로 등록을 먼저 확정하고 이긴 쪽만 실행 | `TownLoadRegistryTest#실행기가_거절해도_자리를_비운다`, `#즉시_실행돼도_자리를_비운다` |
| 실패 뒤 재시도 가능 | 자기 자리를 먼저 비우고 완료시킨다 | `TownLoadRegistryTest#실패_뒤_다음_요청은_다시_적재한다` |

`computeIfAbsent`의 mapping 함수 안에서 실행기를 부르지 않는다. 그 안에서 적재가 동기적으로
끝나거나 거절되면 완료 처리와 자리 비우기가 map 삽입보다 먼저 일어나 버린 것을 정리하려 들고,
그 순서에 기대는 코드는 실행기 종류에 따라 달라진다.

등록에 이긴 쪽은 **실행 전에 캐시를 한 번 더 본다.** 첫 조회와 등록 사이에 다른 비행이 끝나
게시했을 수 있고, 그것을 놓치면 방금 읽어 온 것을 그대로 다시 읽는다. (이 이중 확인은 코드
근거이며, 그 좁은 틈을 노린 전용 테스트는 두지 않았다 — **직접 검증하지 않았다.**)

응답 조립은 **공유 적재 스레드가 아니라 공용 실행기**에서 돈다. 페이지를 만드는 일에는
북마크 조회(사용자별이라 캐시에 담을 수 없는 유일한 값)가 끼어 DB를 한 번 더 읽는데, 그것을
적재 풀에서 돌리면 풀이 응답 조립으로 막혀 다른 동네의 적재가 뒤로 밀린다. 전부 캐시 적중인
요청은 이미 그 실행기 위에 있어 스레드를 한 번 더 넘기지 않는다. (역시 **코드 근거이며 전용
테스트는 없다** — 다만 실행기를 막는 `TownCacheBudgetIT#실행기_큐가_막혀도_예산_안에_끊긴다`가
요청 본문이 그 실행기 위에서 돈다는 사실에는 의존한다.)

### 3.7 요청 총 대기 예산

**시계가 요청 진입 시각부터 돈다.** 예산 안에 들어가는 것은 적재 대기만이 아니다 — 동네 트리를
푸는 조회, 커넥션을 얻는 대기, 번호 관측, 실행기 큐 대기가 전부 사용자가 기다리는 시간이다.
동기 작업을 먼저 하고 나중에 future에 timeout을 붙이면 그 앞의 DB 대기는 어떤 예산에도 걸리지
않는다. 캐시 전부 적중인 요청도 예외가 아니다.

| 막은 것 | 구현 | 테스트 | 검증 |
|---|---|---|---|
| 느린 적재 | `TownPlaceListService#awaitAll` | `TownCacheConcurrencyIT#예산을_넘기면_재시도_가능_오류다` | 직접 |
| **번호 관측** (캐시 전부 적중이라 적재 없음) | `#townPath`가 관측보다 먼저 시계를 건다 | `TownCacheBudgetIT#번호_관측이_느려도_예산_안에_끊긴다` | 직접 |
| **실행기 큐 대기** (본문이 시작조차 못 함) | 같은 자리 | `TownCacheBudgetIT#실행기_큐가_막혀도_예산_안에_끊긴다` | 직접 |
| **커넥션 대기** (첫 검증 조회부터 막힘) | 같은 자리 | `TownCacheBudgetIT#커넥션이_없어도_예산_안에_끊긴다` | 직접 |
| 다른 번호로 성공 응답 금지 | `#awaitAll`의 오류 변환 | `TownCacheConcurrencyIT#예산을_넘기면_재시도_가능_오류다` | 직접 |

세 보강 테스트는 **적재를 건드리지 않는다.** 캐시를 미리 채워 적재가 일어날 이유를 없앤 뒤 그
앞뒤만 막는다 — 그래서 "동기 작업을 먼저 하고 나중에 적재 future에 timeout을 붙인" 구현으로는
통과할 수 없다. 오류 타입만이 아니라 **걸린 시간**도 함께 재는데(막음 3,000ms · 예산 400ms ·
상한 2,000ms), 막은 시간을 다 기다렸다가 오류를 내는 구현은 타입만 보면 통과하기 때문이다.

커넥션 대기 테스트는 풀의 커넥션 4개를 전부 점유해 만든다. 베이스(`MySqlContainerSupport`)의
`@DynamicPropertySource`가 상위라 나중에 불려 풀 크기를 하위에서 덮을 수 없다.

**직접 검증하지 않은 것:** SQL 실행 자체가 느린 경우(문장이 시작은 했으나 오래 걸리는 경우)는
막아 보지 않았다. 코드 구조상 같은 시계 아래 있지만, 이 문서에서 "검증했다"고 적지 않는다.

### 3.7-b 인프라 오류는 재시도 가능으로 번역한다

예산이 끊는 것과 **DB를 아예 못 잡는 것**은 다른 경로다. 커넥션을 못 얻었거나 트랜잭션을 못
열었다는 것은 요청이 잘못됐다는 뜻이 아니므로, 계약이 약속한 명시적 재시도 가능 오류로 바꾼다.
번역하지 않으면 동네 트리 조회나 번호 관측이 DB를 못 잡았을 때 500이 나가고, 클라이언트는 고칠
곳을 잘못 짚는다.

적재 실패는 `TownPlaceListService`가 이미 같은 오류로 바꾼다.
`PlaceListRequestOrchestrator#toRetryable`이 덮는 것은 그 **앞뒤**다 — 검증 조회, 번호 관측,
응답을 만들며 내는 북마크 조회.

| 요구 | 테스트 |
|---|---|
| 번호 관측의 DB 실패 → `PLACE_SNAPSHOT_SYNCING`, 예산을 다 쓰지 않고 즉시 | `TownCacheBudgetIT#번호_관측의_DB_실패는_재시도_가능_오류다` |
| **재관측**의 DB 실패도 같은 오류 | `TownCacheConcurrencyIT#재관측의_DB_실패도_재시도_가능_오류다` |
| **전부 503으로 숨기지 않는다** — 잘못된 요청은 그대로 | `TownCacheBudgetIT#잘못된_요청은_재시도_가능_오류로_뭉개지지_않는다` |

번역해야 할 자리가 <b>둘</b>이다. 첫 관측은 요청 본문의 `try` 블록에서 터지지만, 첫 페이지
<b>재관측</b>은 `thenCompose` 안에서 돌아 실패가 exceptional future로 나온다. 한쪽만 번역하면
재관측이 DB를 못 잡았을 때만 500이 나가고, 그 경로는 적재 중 번호가 오른 드문 경우에만 밟히므로
운영에서 한참 뒤에야 드러난다.

`DataAccessException`과 `TransactionException`만 번역한다. `BusinessException`(없는 동네·잘못된
커서·만료)과 인증 오류는 그대로 두어 400/401이 유지되고, `NullPointerException` 같은 프로그래밍
오류도 그대로 둬 500으로 드러나게 한다 — 버그를 "잠시 뒤 다시"로 덮으면 영영 고쳐지지 않는다.

이 구멍은 지금은 눈에 띄지 않는다. 커넥션 점유 테스트가 통과하는 이유가 Hikari 기본 timeout
30초보다 예산 400ms가 먼저 끊기 때문이라, 운영에서 Hikari timeout이 예산보다 짧게 잡히는 순간
정확히 이 자리로 500이 난다.

### 3.8 정적 5축과 페이지

| 요구 | 테스트 |
|---|---|
| 다섯 축이 각자의 순서 | `TownCacheListFlowIT#정적_다섯_축이_각각_자기_순서를_낸다` |
| **기존 전역 경로와 동등** | `TownCacheListFlowIT#다섯_축_모두_전역_경로와_같은_순서를_낸다` |
| tie-break(인기·최신·평점) | `TownPlacesTest#인기순은_...`, `#최신순은_...`, `#평점순은_평점_리뷰수_id_순으로_끊는다` |
| 평점 커서의 정수 경계 | `TownPlacesTest#평점순_커서는_double로_실려도_정수_경계를_찾는다` |
| 태그 필터 | `TownPlacesTest#태그_필터가_맞지_않는_원소는_페이지에_없다` |
| 커서 페이징(누락 0·중복 0) | `TownCacheListFlowIT#커서_페이징은_항목을_흘리지도_겹치지도_않는다` |
| 여러 동네 합집합·병합 | `TownListReaderTest#여러_동네를_한_순서로_병합한다`, `#커서가_가리키는_다음_자리에서_이어진다` |
| 빈 동네 | `TownListReaderTest#빈_동네가_섞여도_나머지가_나온다`, `#전부_빈_동네면_빈_페이지다`, `TownCacheListFlowIT#장소가_없는_동네는_빈_페이지를_낸다` |
| 다음 커서가 실제 번호를 싣는다 | `TownCacheListFlowIT#다음_커서는_서빙한_번호를_싣는다` |
| 다섯 축이 같은 엔트리 객체를 공유 | `TownPlacesTest#다섯_축은_같은_엔트리_객체를_공유한다` |
| **표시 홀더 때문에 행이 빠지지 않는다** | `TownCacheListFlowIT#전역_표시_홀더가_비어_있어도_행이_빠지지_않는다` |

### 3.9 표시값과 정기 배치

| 요구 | 구현 | 테스트 |
|---|---|---|
| 이름·썸네일만은 bump 없음 | 지문 비교 + `PlaceImageFieldUpdater`·`AdminTagService`에 동네 bump 없음 | 3.1의 표시-전용 테스트 셋 |
| 다음 성공 전체 배치가 **처리 대상 모든 동네** bump | `PlaceStatsBatchProcessor#markBatchRoundChanged` | `TownVersionBumpIT#정기_전체_배치는_값이_안_바뀐_동네도_올린다`, `#점수_배치도_전_동네를_올린다` |
| 그 뒤 **그 동네의 적재**까지 stale 허용 | 캐시 키가 번호 | `TownCacheListFlowIT#표시값만_바뀐_변경은_배치_뒤_재적재에서_나타난다` |
| **실제 배치 실패 시 원본+bump 원자성** | 배치 트랜잭션 안 | `TownVersionBumpIT#실제_배치가_롤백되면_원본과_bump가_함께_사라진다` |
| 짝이 되는 성공 경로 | 같은 자리 | `TownVersionBumpIT#실제_배치가_커밋되면_원본과_bump가_함께_남는다` |

롤백 테스트는 **실제 `recalculateCounts`를 바깥 트랜잭션에 참여시킨 뒤 그 트랜잭션을 깬다.**
`markAllTownsChanged`만 부르고 던지는 것은 배치 경로의 증거가 아니다 — 배치가 자기 트랜잭션에서
그 호출을 하는지조차 보지 않기 때문이다. 원본 쪽 단언의 재료로 일부러 틀린 카운트를 심어 두고,
롤백 뒤 그 값이 **그대로 틀린 채**이고 번호도 그대로인지 본다.

배치 한 회차가 목록 쪽에 남기는 쓰기가 "전역 한 행"에서 "전역 한 행 + 동네마다 한 행"으로 늘었다.
`PlaceStatsBatchProcessorIT#카운트_회차는_값이_달라진_행만_쓴다`가 그 몫을 데이터에서 세어 빼도록
바뀌었다(`listRowWritesPerRound`).

## 4. 남긴 경계와 그 이유

### 전역 스냅샷 기계를 남겼다 — 거리 호환에 필요

`SnapshotBox`/`SnapshotLoader`/`SnapshotInstaller`/`SnapshotLoadCoordinator`/전역
`place_list_snapshot_metadata`와 그 `markChanged(...)` 호출부를 **그대로 뒀다.** 거리순이
`SortedPlaces#distanceCandidates`와 전역 표시 홀더 위에 서 있고, 거리순은 이번 변경·비교·최적화의
범위 밖이다.

**그래서 전역 전량 스냅샷 한 벌의 상주 비용은 그대로 남아 있다.** 뒤따르는 비교에서 "동네별
적재로 전량 상주가 없어졌다"고 말하면 안 된다. 줄어들 수 있는 것은 정적 5축 경로가 새로 짓는
몫이고, 앱 전체 메모리는 그 몫과 전역 보관 비용을 함께 봐야 한다.

거리순 경로에는 표시값을 못 찾은 행을 결과에서 빼는 기존 동작이 남아 있다
(`PlaceService#respond(..., dropMissingDisplays = true)`). 정적 5축의 새 경로에는 그 분기가 없고,
동네 객체가 표시값을 못 들고 있으면 `IllegalStateException`으로 드러난다.

전역 경로를 검증하던 `PlaceListSnapshotCatchUpIT`와 `PlaceListRequestOrchestratorTest`는
`list-source = GLOBAL_SNAPSHOT`으로 **명시 고정**했다. 기본값이 바뀌었다고 그 검증이 조용히 다른
경로를 돌면 무엇을 통과시켰는지가 사라진다.

### 비교 경계

`PlaceListProperties.ListSource`: `GLOBAL_SNAPSHOT` / `TOWN_PRESORTED`(기본) /
`TOWN_REQUEST_SORT` / `DB_DIRECT`.

`TOWN_REQUEST_SORT`는 **실제로 배열을 만들지 않는다.** `TownSourceLoader`가 그 모드에서
`TownPlaces.objectsOnly`로 적재해 5축 배열도 사전 정렬도 생략하고, 조회는 필터·커서 건너뛰기 후
합집합을 **한 번만** 정렬한다. 두 모양의 준비 비용이 실제로 달라야 비교가 성립한다 — objects-only가
몰래 5축을 세워 두면 "요청 시점 정렬"의 준비·메모리 비용이 사전 정렬의 것과 같아져 비교가
통째로 무의미해진다. 결과 동등성은
`TownPlacesTest#요청_시점_정렬도_같은_순서를_낸다`와
`TownListReaderTest#요청_시점_정렬도_다중_동네에서_같은_순서를_낸다`가 문다.

**`DB_DIRECT`는 이번 작업에서 요청 경로에 연결하지 않았다.** 고르면 `IllegalStateException`으로
거절된다. **다음 비교 단계에서 연결해야 하고, 네 구조가 모두 같은 정적 5축 결과를 낸다는 것을
확인하기 전에는 성능 비교를 시작하지 않는다.**

## 5. 아직 확정하지 않은 것

- **캐시 예산.** `solply.place-list-town-cache.max-places` 기본 **200,000**은 **근거 없는 잠정값**이다.
  설정으로 바꿀 수 있게 두었을 뿐이고, 확정 정책이 아니다. 이 상한은 힙의 엄격한 상한도 아니다 —
  진행 중 요청이 잡은 옛 참조, 적재 중 후보, 미회수 객체는 여기 들어가지 않는다.
- **대기 예산 1,000ms.** 구현·검증할 예산이지 측정된 응답 시간이 아니다.
- **적재 전용 풀 4, 큐 무제한, 첫 페이지 재관측 한도 2.** 전부 측정 근거 없는 임시값이다.
  근거는 "요청 스레드를 DB 대기로 점유하지 않는다"와 "무한 재시도를 두지 않는다"뿐이다.
- **네 구조 중 무엇이 유리한가.** 이번 작업에서 성능 비교·부하 실험을 하지 않았다. 기본값이
  `TOWN_PRESORTED`인 것은 우승 판정이 아니라 새 계약을 실제로 돌리는 경로를 하나 정한 것이다.
- **동시 적재 제한·대기열·선적재의 필요성.** 미결정이다. 지금은 두지 않았다.

## 6. 측정하지 않은 것

- 앱/DB CPU, 할당량·GC·live heap, hit/miss 지연, 처리량, 배열 재사용 횟수.
- 실제 요청 분포. 상위 페이지 위주·짧은 스크롤 가설은 **가설이며 운영 측정 사실이 아니다**.
- 동네별 적재의 실제 비용과 전량 적재와의 대비.
- **과거 63,200 장소 수치는 10배 확대 실험 데이터이며 현재 DB의 실측값이 아니다.** 규모를 말할
  때 이 수를 현재 값처럼 인용하면 안 된다.

## 7. 하지 않은 것

커밋·푸시·PR·배포·이슈 생성, 운영/dev 부하, 다른 컨테이너 종료. 원본 체크아웃과 다른 worktree는
건드리지 않았다. 원본의 `load-test/campaigns/2026-09-21_town-cache-comparison`은 별도 작업자의
소유라 손대지 않았다.
