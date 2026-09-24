> **이전 설계·작업 이력 — 현재 구현 요구사항이나 완료 판정으로 사용하지 마세요.** 현재 기준: [설계 근거와 진행 상황](../../../blog/2026-09-21-place-list-design-evidence.md).

# DB 직접 조회 비교 구조의 읽기 경로 — 계약과 검증

작성일: 2026-09-21. 대상: `TownDbDirectReader`, `TownDbDirectReaderIT`.
정본 설계: [장소 목록 동네 캐시 설계](../design/2026-09-21-place-list-town-cache.md).
범위: 정적 정렬 다섯 축(인기·최신·평점·리뷰 수·북마크 수)만. 거리순은 이번 비교에서 제외한다.

## 1. 무엇을 만들었나

설계 §4의 네 후보 중 **DB 직접 조회**의 읽기 경로다. 다른 세 후보(전역 스냅샷, 동네별 사전정렬,
동네 객체 + 요청 정렬)와 같은 조건에서 견줄 수 있도록, 요구사항 §2의 정합성 계약을 그대로
지키면서 조회만 DB에 맡긴다.

**"DB 직접"의 뜻을 행 수로 고정했다.** 동네 전량을 읽어 온 뒤 자바에서 자르는 것은 이 구조가
아니다. 정렬·태그 필터·커서 seek·절단을 SQL이 끝내고, 앱이 받는 행은 `fetchSize` 이하다.
그 문장은 새로 쓰지 않고 `PlaceListDbQueryRepository`의 것을 부른다 — 동네별 브랜치 UNION ALL,
커버링 인덱스, seek 술어가 이미 그 안에 있고 복사하면 두 경로의 의미론이 갈린다.

## 2. API

```java
public record Page(TownVersions versions, List<PlaceEntry> entries, Map<Long, PlaceView> displays)

public Page read(List<Long> leafTownIds, PlaceSortType sort, Long mainTagId,
                 List<Long> subTagAIds, List<Long> subTagBIds,
                 PlaceListCursor cursor, int fetchSize)
```

호출 예 — `size + 1`을 떠서 다음 페이지 유무를 보는 기존 규칙 그대로다.

```java
Page page = reader.read(leafTownIds, PlaceSortType.POPULAR,
        mainTagId, subTagAIds, subTagBIds, cursor, size + 1);
boolean hasNext = page.entries().size() > size;
String scope = page.versions().scope();   // 다음 커서에 실을 범위 표현
```

- `versions`에는 **요청 범위의 모든 leaf 동네**가 들어 있다. 장소가 하나도 걸리지 않은 동네도
  빠지지 않는다 — 빠지면 다음 커서의 범위 표현이 달라져 같은 요청의 스크롤이 조용히 다른 범위가
  된다.
- `entries`는 페이지 순서 그대로이고 비어 있을 수 있다. 빈 결과는 정상이다.
- `displays`의 키는 `entries`의 place_id 전부다. "표시값을 찾지 못함"이라는 상태가 없다.
- `fetchSize`와 `req.size` 해석은 호출부의 경계다. 이 클래스는 상한만 받는다.
- 동기 API다. 커넥션 확보와 실행 대기 예산은 호출부(요청 하나당 1초)가 감싼다.

## 3. 지키는 계약

**한 read view.** REQUIRES_NEW·읽기 전용·REPEATABLE READ 트랜잭션 하나 안에서 (1) leaf 동네
번호를 한 문장으로 읽고 (2) 커서 범위를 견주고 (3) 페이지 문장을 돌리고 (4) 그 place_id로
정렬 키·표시값을 읽는다. 문장은 셋이지만 관측 시점은 하나다. 그래서 돌려주는 번호는 돌려주는
행의 번호다.

**표시값을 따로 읽는 이유는 비용이지 시점이 아니다.** 페이지 문장은 커버링 인덱스 안에서 끝나야
하므로 이름·썸네일 같은 인덱스 밖 컬럼을 그 SELECT에 얹을 수 없다
(`PlaceListDbQueryRepository#findPopularRows` 주석의 계약). 얹으면 걸러 낼 행마다 PRIMARY 룩업이
붙어 이 구조가 재려던 것을 못 재게 된다. 그래서 페이지가 정해진 뒤 그 목록으로만 한 번 더 읽고,
읽는 행 수는 페이지 크기와 같다.

**만료 판정은 범위 표현 하나다.** 커서가 싣고 온 `scope` 문자열이 지금 번호로 만든 것과 다르면
`EXPIRED_PLACE_CURSOR`다. 번호가 오른 경우와 leaf 집합 자체가 달라진 경우를 그 한 비교가 함께
잡는다(`TownVersions#scope`).

**여기서 보지 않는 것.** 커서의 필터 지문·정렬 축 일치는 호출부(`PlaceService`)가 이미 본다.
거리순은 범위 밖이라 인자로 들어오면 거부한다.

## 4. 검증

`TownDbDirectReaderIT` — Testcontainers MySQL 8.0. 대조군은 **같은 픽스처를 `TownSourceLoader`로
적재해 `TownListReader`로 자른 페이지**다. 두 경로 모두 실제 DB를 돌고, 기대 순서를 손으로 적지
않는다 — 손으로 적으면 두 경로가 함께 틀린 상태가 그린이 된다.

| 테스트 | 무엇을 문다 |
|---|---|
| 정적 다섯 축의 첫 페이지 | 다섯 축의 정렬·타이브레이크 등가 |
| 정적 다섯 축의 두 번째 페이지 | keyset seek(등호 분기)과 커서 왕복, 페이지 중복 없음 |
| 태그 필터를 건 두 페이지 | 그룹 내 OR·그룹 간 AND를 SQL 술어와 자바 판정이 공유하는지 |
| 여러 동네 합집합 | 브랜치 합병 순서 = k-way 병합 순서, 빈 동네가 섞여도 같음 |
| 장소가 없는 동네 | 빈 페이지지만 범위에는 남는다 |
| 페이지 행마다 표시값·정렬 키 | 표시값이 행마다 함께 오고 저장값과 같다 |
| 번호가 오른 뒤의 커서 | `EXPIRED_PLACE_CURSOR` |
| 범위가 달라진 커서 | 번호가 그대로여도 leaf 집합이 바뀌면 만료 |
| 번호와 행의 같은 read view | 커밋을 번호 읽기와 페이지 문장 **사이에** 끼워 넣고, 그 뒤의 문장들이 커밋을 보지 못하는지 |

마지막 항목은 순서를 걸림쇠로 못 박는다. 쓰기를 그냥 동시에 돌리고 읽기를 반복하는 검증은
커밋이 실제로 두 문장 사이에 들어갔다는 보장이 없어 read view가 없는 구현도 우연히 통과한다.
그래서 번호 리포지토리를 상속해 **실제 문장을 돌린 직후** 다른 커넥션의 쓰기(원본 삭제 + 번호
올리기, 한 트랜잭션)가 커밋을 마칠 때까지 기다리게 하고, 그 뒤 페이지·표시값 문장이 돈다.
같은 read view면 응답은 옛 번호 + 옛 원본이어야 하고, 다음 읽기는 새 번호 + 사라진 행을 본다.

픽스처는 동점 구간(북마크 4·4·4·2가 두 동네에 걸침), 같은 초에 만든 둘(최신순만 id 내림차순),
같은 평점·다른 리뷰 수 셋(평점순 2단 seek), 리뷰 0건, 장소 없는 동네를 심는다.

### 실행 결과

단독 슬롯에서 read view 테스트를 결정화한 뒤 돌린 것이 최종 근거다. 앞서 core의 clean build와
겹쳤을 수 있는 실행은 근거에서 뺀다.

```
./gradlew test --tests 'org.sopt.solply_server.domain.place.cache.town.TownDbDirectReaderIT' --rerun-tasks
→ BUILD SUCCESSFUL, exit 0
```

`build/test-results/test/TEST-org.sopt.solply_server.domain.place.cache.town.TownDbDirectReaderIT.xml`:
tests 9 · failures 0 · errors 0 · skipped 0 · 2.714s (MySQL 8.0 Testcontainers, 2026-09-21).
로그와 XML 사본은 `/tmp/claude-501/task_8b34ea2ac11b/`의 `it-run-final.log`,
`final-result.xml`에 남겼다. 같은 창에서 다른 IT나 전체 빌드를 함께 돌리지 않았다.

## 5. 한계

- **성능은 아직 재지 않았다.** 이 문서는 계약과 등가의 기록이고, hit/miss 지연·앱/DB CPU·
  문장당 비용 비교는 별도 캠페인의 몫이다. 구조가 붙었다는 사실을 이득의 근거로 쓰지 않는다.
- 요청 경로(`PlaceListRequestOrchestrator`·`PlaceService`)에 연결하는 일은 이 작업의 범위 밖이다.
  `ListSource.DB_DIRECT`를 고르면 아직 거부된다.
- 거리순은 이 구조가 맡지 않는다. 사용자 명시 제외 사항이며 별도 최적화·벤치로 재도입하지 않는다.
- 문장 수는 요청당 셋(번호·페이지·표시값)이다. 하나로 줄이려면 페이지 SELECT에 표시 컬럼을
  얹어야 하는데 그것은 커버링을 깨뜨린다. 둘 중 어느 쪽이 싼지는 측정으로만 말할 수 있다.
