> **이전 설계의 실행 계획입니다.** 현재 작업은 [현재 실행 계획](../../../superpowers/plans/2026-09-21-place-list-version-retention.md)을 따릅니다.

# 구현 계획: 장소 목록 동네 버전·동네 캐시

기준 형상: `develop@64a97e08274303199b33244ba579adde22e4437d`.
정본 요구사항: [설계](../design/2026-09-21-place-list-town-cache.md), 인계 (작업용 문서 정리로 삭제), 사전 조사 (작업용 문서 정리로 삭제).

이 문서는 **무엇을 어디에 짓고 무엇으로 확인하는지**만 적는다. 성능 우승 판정과 부하 실험은 이번 범위가 아니다.

## 0. 현재 코드에서 확인한 사실

- `place_stats`가 목록의 원본이다. `town_id`, `tag_bitmask`, 정렬 키 다섯, 표시값(`name`, `thumbnail_file_key`, `main_tag_id`)이 한 행에 있다. 삭제는 행 제거다.
- `place_list_snapshot_metadata`(V46)는 전역 한 행 `(revision, cursor_version)`이다. 동네 개념이 없다.
- `SortedPlaces`는 이미 `Map<정렬축, Map<townId, PlaceEntry[]>>`다. 바꿀 것은 **적재·소유·버전·수명 단위**다.
- `PlaceService.listPlaces`는 전역 `PlaceViewHolder`에서 표시값을 찾고 **없으면 그 행을 결과에서 뺀다**. 이 경로를 없애야 한다.
- `PlaceListCursor.FORMAT_VERSION`은 `v7`이고 마지막 필드가 전역 `version` 하나다.
- 쓰기 경로의 `SnapshotCursorPolicy`는 **어드민 체크박스**(`restartsPlaceList`)가 고른다. 새 계약은 변경 성격으로 서버가 판단한다.
- 마이그레이션 최신 번호는 `V47`이다. 새 파일은 `V48`.
- Caffeine 의존성은 `build.gradle`에 있으나 목록 캐시에는 쓰이지 않는다.

## 1. 경계: 무엇을 바꾸고 무엇을 남기나

| 경로 | 처리 |
|---|---|
| 정적 5축(인기/최신/평점/리뷰수/북마크수) | **새 동네 캐시 경로** |
| 거리순 | 기존 전역 스냅샷 경로 **그대로**. 최적화·벤치·설계 질문 없음 |
| 북마크 검색 | 기존 DB 경로 그대로 |

**전역 스냅샷 기계(`SnapshotBox`/`SnapshotLoader`/`SnapshotInstaller`/`SnapshotLoadCoordinator`/전역 metadata)를 남긴다.** 거리순이 `SortedPlaces#distanceCandidates`와 전역 표시 홀더 위에 서 있어서다. 이것은 "기존 전역 경로 보존이 거리 호환에 필요"한 경우에 해당하므로 여기 명시한다. 전역 `markChanged(...)` 호출부도 지금 그대로 둔다 — 빼면 거리순의 커서 만료 동작이 바뀐다.

## 2. DB: 동네별 단일 version

`V48__place_list_town_versions.sql`

```sql
CREATE TABLE place_list_town_versions (
    town_id    BIGINT      NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 1,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (town_id)
);
```

- 번호는 **동네마다 하나**다. `revision`/`cursorVersion` 두 번호를 동네별로 복제하지 않는다.
- 행이 없는 동네는 **version 0**으로 읽는다. 별도 시딩을 두지 않는다 — 첫 bump가 `INSERT ... ON DUPLICATE KEY UPDATE version = version + 1`로 행을 만든다.
- 상태값·구버전 보관·TTL 컬럼을 두지 않는다.

### 자동 bump 분류

| 변경 | bump |
|---|---|
| 장소 생성/삭제/활성화, 동네 이동, 태그 재지정(비트마스크), 정렬 키값 | 영향 동네 |
| 동네 이동 | 출발·도착 **둘 다** |
| 이름·썸네일만 | **없음** |
| 정기 전체 배치 성공 | `place_stats`에 있는 **모든 동네** |

이동/삭제를 빠뜨리지 않는 방법: 쓰기 전후로 `SELECT DISTINCT town_id FROM place_stats WHERE place_id IN (...)`를 각각 읽고 **합집합**을 bump한다. 어드민 쓰기는 드물어 두 번의 SELECT가 문제되지 않는다.

전체 배치는 한 문장이다:
```sql
INSERT INTO place_list_town_versions (town_id, version)
SELECT DISTINCT town_id FROM place_stats
ON DUPLICATE KEY UPDATE version = version + 1
```
배치 트랜잭션 안에서 돌므로 배치가 롤백되면 bump도 함께 사라진다.

## 3. 커서: 범위와 버전을 함께 싣는다

`PlaceListCursor`의 마지막 필드 `long version` → `String scope`, `FORMAT_VERSION` `v7` → `v8`.

- 동네 경로: `t@<townId>:<v>,<townId>:<v>,...` (townId 오름차순)
- 거리순·전역 경로: `g@<cursorVersion>`

scope는 **leaf 동네 집합과 각 버전을 한 문자열에 담는다.** 다음 요청에서 leaf를 다시 풀어 버전을 읽고 scope를 만들어 **문자열 동등 비교**한다. 다르면 만료다. 이 한 비교가 (a) 버전 상승과 (b) leaf 범위 변화를 동시에 잡는다 — 같은 `townId` 파라미터로 leaf 집합이 조용히 바뀌는 경우가 후자다.

`filterPrint`(동네·태그)와 `sort`는 지금 그대로 둔다.

## 4. 캐시

### 소유 단위

`TownPlaces` — 한 동네의 한 버전을 통째로 소유하는 불변 객체.

```java
record TownPlaces(long townId, long version,
                  Map<PlaceSortType, PlaceEntry[]> orders,   // 정적 5축
                  Map<Long, PlaceView> displays,             // 표시값을 함께 소유
                  int placeCount)
```

- 정렬 배열 다섯은 **같은 `PlaceEntry` 객체를 참조**한다. 축마다 복제하지 않는다.
- **표시값을 이 객체가 들고 있다.** 전역 홀더를 보지 않으므로 "홀더에 없어서 행이 빠지는" 경로가 구조적으로 사라진다.
- ID 배열 + 전역 맵 구성은 채택하지 않는다(설계 §3).

### 용량

Caffeine `maximumWeight`, weigher = `placeCount`. 설정 키 `solply.place-list-town-cache.max-places`, **잠정 기본 200_000**.

**근거 없는 잠정값이다.** "무제한은 아니다"는 것 외에 이 수를 고른 이유가 없다. 최종 예산은 실측 뒤 확정한다. (과거 63,200 장소 수치는 10배 확대 실험 데이터이며 현재 DB의 실측값이 아니라, 여기서 근거로 쓰지 않는다.)

eviction은 Caffeine의 최근성·빈도 정책에 맡긴다. TTL·soft TTL·별도 revision·Redis를 두지 않는다. **eviction 자체는 커서 만료가 아니다** — 버전이 같으면 다시 적재해 이어간다.

### 적재 (RR read view)

`TownSourceLoader.load(townIds)` — 하나의 `REQUIRES_NEW` + `REPEATABLE_READ` + readOnly 트랜잭션 안에서

1. `SELECT town_id, version FROM place_list_town_versions WHERE town_id IN (...)`
2. `SELECT ... FROM place_stats WHERE town_id IN (...)`

를 읽는다. 그래서 **실제 적재한 버전과 원본이 같은 시점**이다. 요청이 v10을 봤다는 이유로 나중에 읽은 v11 데이터를 v10으로 표기하는 일이 생길 수 없다.

### 공유 적재(single-flight)

`TownLoadRegistry`: `ConcurrentHashMap<TownCacheKey, CompletableFuture<TownPlaces>>`.

- 같은 `(townId, version)`은 한 번만 적재하고 나눠 쓴다.
- 대기자는 공유 future를 **직접 건드리지 않는다.** `flight.copy()`에 `orTimeout`을 건다 — 한 대기자의 timeout/cancel이 공유 작업을 취소하지 못한다.
- 실패는 그 flight의 대기자에게 **즉시** 전파한다(요청 예산까지 매달리지 않는다).
- 캐시 키에 버전이 들어 있으므로 **늦게 끝난 옛 적재는 자기 키에만 쓴다.** 새 버전 항목을 덮을 경로가 없다.
- 적재는 전용 데몬 풀(`town-place-loader`, 고정 4)에서 돈다. 요청 스레드에서 돌면 자기 예산을 넘겨도 멈출 수 없다.

### 대기 예산

`solply.place-list-town-cache.request-budget-ms`, 기본 **1000**. 요청 **하나의 총 예산**이다. 동네가 셋이어도 1초다(동네마다 1초씩 늘어나지 않는다). 예산 초과·확보 실패는 `PLACE_SNAPSHOT_SYNCING`(503, 재시도 가능)이다. 다른 버전 데이터로 성공 응답하지 않는다.

## 5. 요청 흐름

```
leaf 동네 해석
 → 한 read view에서 관련 동네 버전 전부 읽기         (TownVersionRepository#readInCurrentTransaction)
 → 커서 있음: scope 문자열 비교 → 다르면 EXPIRED
 → (townId, version)으로 캐시 조회
     hit  : 참조를 잡는다
     miss : 공유 적재에 합류, 남은 예산만큼 대기
 → 적재가 관측한 버전이 요청 관측과 다르면
     첫 페이지 : 제한 재관측·재시도(최대 2회, 예산 안에서)
     커서 있음 : 현재 버전을 다시 읽어 EXPIRED 판정
 → 확보한 동네 객체들로 정렬/필터/페이지
 → 확보한 실제 버전으로 다음 커서 생성
```

"확인 후 커밋"은 응답을 막지 않는다 — 버전과 데이터를 한 번 확보하면 그 참조로 끝까지 응답한다.

## 6. 새 파일 / 고칠 파일

### 새로 짓는다 — `domain/place/cache/town/`

| 파일 | 책임 |
|---|---|
| `TownVersions.java` | townId→version 불변 맵, scope 인코딩/디코딩, 동등 비교 |
| `TownVersionRepository.java` | 버전 읽기(한 read view)·bump·전체 bump·placeIds의 town 조회 |
| `TownVersionService.java` | `MANDATORY` 트랜잭션에서 flush 후 bump |
| `TownCacheKey.java` | `(townId, version)` |
| `TownPlaces.java` | 한 동네 한 버전의 정렬 배열 다섯 + 표시값 |
| `TownPlacesCache.java` | Caffeine weighted 캐시 |
| `TownSourceLoader.java` | RR read view에서 버전+원본 동시 적재 |
| `TownLoadRegistry.java` | 동일 키 공유 적재, 실패 전파, 대기자 격리 |
| `TownListReader.java` | 여러 동네 객체 위의 5축 페이지·다중 동네 머지 |
| `TownPlaceListService.java` | 요청 흐름 전체 |
| `config/PlaceListTownCacheProperties.java` | `max-places`, `request-budget-ms`, `loader-threads` |

### 고친다

| 파일 | 변경 |
|---|---|
| `util/PlaceListCursor.java` | `version` → `scope`, `v8` |
| `service/PlaceService.java` | 정적 5축을 동네 경로로, 거리순은 기존 경로 유지, 표시 홀더 탈락 경로 제거 |
| `service/PlaceListRequestOrchestrator.java` | 정렬축으로 분기 |
| `config/PlaceListProperties.java` | 비교 경계 enum `ListSource` 추가 |
| `admin/place/service/AdminPlaceService.java` | 쓰기 전후 town 합집합 bump |
| `admin/town/service/AdminTownService.java` | 활성화 시 대상 동네 bump |
| `place/service/PlaceStatsBatchProcessor.java` | 전체 배치 성공 시 전 동네 bump(같은 트랜잭션) |
| `place/service/BookmarkCountDeltaProcessor.java` | 델타가 닿은 동네 bump |
| `admin/place/service/PlaceImageFieldUpdater.java` | town bump **없음**(표시값만) |
| `admin/tag/service/AdminTagService.java` | 이름/활성 토글은 town bump **없음** |

### 비교 경계

`PlaceListProperties.ListSource`: `GLOBAL_SNAPSHOT`(기존 전량), `TOWN_PRESORTED`(기본), `TOWN_REQUEST_SORT`(동네 객체 + 요청 시 정렬), `DB_DIRECT`.
이번 Task에서는 앞의 셋만 돌게 만든다. `DB_DIRECT`는 `PlaceListDbQueryRepository`가 요청 경로에 연결돼 있지 않아 **연결 작업이 남아 있음을 상수와 문서에 남긴다.** 측정·우승 판정은 하지 않는다.

## 7. 테스트

### 단위

| 테스트 | 확인 |
|---|---|
| `TownVersionsTest` | scope 인코딩 왕복, leaf 집합 변화 감지, 순서 정규화 |
| `TownPlacesTest` | 5축 정렬·tie-break, 태그 필터, 커서 seek, 빈 동네 |
| `TownListReaderTest` | 다중 동네 머지, 페이지 경계, 합집합, 빈 결과 |
| `TownLoadRegistryTest` | 동일 키 공유, 한 대기자 timeout이 공유 작업을 취소하지 않음, 실패 즉시 전파, 늦은 옛 적재가 새 항목을 덮지 않음 |
| `TownPlacesCacheTest` | weight 기반 eviction 후 같은 버전 재적재 |
| `PlaceListCursorTest`(기존 보강) | v8 포맷, 옛 토큰 거부 |

### MySQL 통합 (Testcontainers)

| 테스트 | 확인 |
|---|---|
| `TownVersionBumpIT` | 원본 변경과 bump의 원자 커밋/롤백, 생성·삭제·활성화 |
| `TownVersionMoveIT` | 동네 이동이 출발·도착 둘 다 bump |
| `TownVersionBatchIT` | 정기 전체 배치 성공 시 전 동네 bump, 배치 롤백 시 bump도 없음, 표시값만 바뀐 동네도 포함 |
| `TownVersionReadViewIT` | 여러 동네 버전을 한 read view에서 관측, 적재의 버전+원본 동시점 |
| `TownCacheListFlowIT` | 5축 결과가 기존 전역 경로와 동등, 커서 페이징, 인스턴스 이동(캐시 비운 뒤 같은 커서), eviction 후 재적재, 적재 중 버전 변화 |
| `TownCacheConcurrencyIT` | latch로 맞춘 동시 요청의 공유 적재, 적재 중 bump 경쟁, 예산 초과 시 재시도 가능 오류 |

### 실행

`./gradlew build` 전체. Docker daemon이 필요하다 — 붙지 않으면 MySQL 검증을 H2나 skip으로 대체하지 않고 그대로 보고한다.

## 8. 이번 작업에서 하지 않는 것

- 성능 우승 판정, 부하 실험, 거리순 최적화·벤치.
- 캐시 예산 최종 확정(잠정값과 이유만 기록).
- 커밋·푸시·PR·배포.
