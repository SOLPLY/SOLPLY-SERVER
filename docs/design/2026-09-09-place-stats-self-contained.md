# 2026-09-09 · 스냅샷 원천을 place_stats 한 테이블로 — 이름·좌표·메인 태그의 비정규화

> **지위: #401 브랜치 위에 얹는 확정 설계다.** 별도 이슈 없이 진행한다(사용자 결정, 2026-09-09).
> 성공 조건은 측정이 아니라 구조다 — 재빌드 문장 ①이 `place_stats` 단독이 되고, 동치 IT가
> 단언을 바꾸지 않고 통과하며, 응답이 바뀌지 않는 것. 재빌드 소요는 기록만 하고 판정에 쓰지 않는다.

## 1. 출발점 — "조인이 없다"가 반쪽만 참이었다

V34의 요점은 목록 조회가 `place_stats` 하나로 끝난다는 것이었다. 그 말은 DB 경로의 정렬 넷
(인기·최신·평점·카운트)에 대해 정확하다. 예외가 둘 있었다.

- **거리순 DB 경로**는 좌표 때문에 `places`와 JOIN한다. 좌표가 `places`에만 있어서다.
- **스냅샷 로더의 문장 ①**은 `places`(이름·좌표)와 MAIN 태그 파생 테이블(`place_tag ⋈ tags
  WHERE type='MAIN'`) 둘을 JOIN한다. DB 경로는 결과 행의 id로 엔티티를 따로 읽어 표시값을
  채우므로 목록 쿼리에 이름이 필요 없었지만, 스냅샷은 재빌드 한 번에 표시값까지 담아야 한다.

이 문서는 둘째 예외를 없앤다. `place_stats`에 없던 세 값 — 이름·좌표·메인 태그 id — 를
옮겨 문장 ①을 `SELECT … FROM place_stats ps` 단독으로 만든다.

## 2. 무엇을 옮기고 무엇을 안 옮기나

| 값 | 어디서 왔나 | 옮긴 뒤 | 쓰기 경로 변화 |
|---|---|---|---|
| `name` | `places.name` | `place_stats.name` NOT NULL | **이름만 고친 수정도 upsert를 부른다** (§4) |
| `latitude`·`longitude` | `places` | `place_stats` NULL 허용 | 없음 — 좌표는 이미 소속 열쇠라 upsert가 돌던 경로 |
| `main_tag_id` | 파생 테이블 | `place_stats.main_tag_id` NULL 허용 | 없음 — 태그 변경은 집합이 바뀌어 upsert가 돌던 경로 |

안 옮기는 것과 이유:

- **썸네일(문장 ②)·태그 전량(문장 ③)** — 이 문서의 범위 밖이다. 썸네일은 장소당 여러 행이라
  한 테이블로 접히지 않고, #401 §4-3에서 SQL로 한 건만 고르는 안이 측정으로 기각됐다.
- **DB 경로 거리순의 `places` JOIN** — 좌표가 옮겨지면 뗄 수 있지만 DB 경로는 2단계 철거
  예정(#397 후속)이라 손대지 않는다. PK 룩업 JOIN이라 계획이 흔들릴 자유도도 없다.
- **인덱스** — 네 컬럼 모두 정렬·필터 축이 아니다. 로더는 전량 테이블 스캔이라 커버링이 의미
  없고, DB 경로의 두 커버링 인덱스는 그대로 커버링이다.
- **`main_tag_id`의 FK** — `tag_bitmask`와 같은 파생·표시 값이다. 태그 삭제 경로가 없어
  고아가 생길 길이 없고, FK를 걸면 어드민 태그 쓰기와 upsert 사이에 잠금 관계가 새로 생긴다.

## 3. 기각한 안

**메인 태그를 `tag_bitmask`에서 유도한다(컬럼 추가 없음).** `tag_bitmask & MAIN 태그 마스크`의
최하위 비트가 메인 태그다. 태그 타입이 불변(`TAG_TYPE_IMMUTABLE`)이고 어드민 검증이 MAIN 하나를
강제하니 정상 데이터에서는 맞는다. 기각한 이유는 규칙이 바뀌기 때문이다 — 지금 규칙은
"`place_tag.id` 오름차순 첫 MAIN"이고 엔티티 경로(`Place#getMainTag`, bag 순서)와 로더 IT가
그 규칙을 못 박고 있다. 유도하면 MAIN이 둘인 비정상 데이터에서 "tag id가 작은 쪽"으로 갈리고,
표시값이 필터 컬럼의 비트 배치에 묶인다. 이름·좌표는 어차피 컬럼이 필요해서 컬럼 하나를
아끼는 값이 없다.

**표시 컬럼 전용 UPDATE 문장을 패치 경로에 둔다.** 이름 수정 때 `UPDATE place_stats ps JOIN
places p SET ps.name = p.name`만 돌리는 안. 같은 컬럼을 두 문장이 쓰게 되어 `PlaceStats` javadoc의
"칸마다 주인이 하나, 겹치지 않는다" 계약이 약해지고, 표시 컬럼이 늘 때마다 두 문장을 함께
고쳐야 한다. 어드민 빈도에서 문장 하나를 더 도는 비용은 판단 근거가 아니다.

## 4. 쓰기 경로 — 어드민 소유 컬럼은 여전히 한 문장이 짓는다

`upsertRowsForActivePlaces`·`rebuildRowsFromSource`의 SELECT와 `ON DUPLICATE KEY UPDATE`에
네 컬럼을 더한다. 카운트·점수 칸은 여전히 건드리지 않는다. 메인 태그는 파생 테이블로 뽑는다:

```sql
LEFT JOIN (
    SELECT pt.place_id, MIN(pt.id) AS pt_id
    FROM place_tag pt
    JOIN tags t ON t.id = pt.tag_id
    WHERE t.type = 'MAIN'
    GROUP BY pt.place_id
) mm ON mm.place_id = p.id
LEFT JOIN place_tag mpt ON mpt.id = mm.pt_id      -- mpt.tag_id가 main_tag_id
```

"첫 MAIN = `place_tag.id` 오름차순 첫 행, 활성 무관"이라는 규칙을 이 형태 그대로 V40 백필·
upsert·rebuild 세 곳이 쓴다. SQL이라 공유할 수 없어 복사되지만, 규칙의 검증은
`PlaceStatsRepositoryIT` 한 곳(upsert 결과)에 둔다.

`AdminPlaceService#updatePlace`는 소속 열쇠가 같아도 upsert를 부른다. 이름이 `place_stats`의
칸이 된 순간 "표시값만 바뀐 수정은 파생 컬럼이 그대로"라는 전제가 깨졌기 때문이다. 그 뒤의
분기(패치냐 전량 재빌드냐)는 그대로다 — 열쇠가 같으면 표시값 패치, 다르면 재빌드.

## 5. 읽기 경로

문장 ①에서 JOIN 둘과 `ORDER BY ps.place_id`, 그리고 직전 id 비교로 하던 중복 스킵을 지운다.
장소당 한 행은 PK가 보장하므로 인접성에 기댈 이유가 없다.

단건 문장(`SINGLE_VIEW_SQL`)은 `FROM place_stats ps WHERE ps.place_id = :placeId`로 바꿔
`ps.name`·`ps.main_tag_id`를 읽는다. 전량과 단건이 **같은 원천**을 읽어야 두 경로의 동치가
성립한다. 부작용 하나 — 비활성 장소는 행이 없어 패치가 no-op이 된다. 지금 javadoc은
"없음과 비활성을 구분해야 패치가 조용히 죽지 않는다"고 적었지만, 비활성 장소는 정렬 배열에
없어 화면에 닿지 않고 재활성은 전량 재빌드를 부르므로 그 구분에 값이 없다. javadoc을 이
근거로 바꿔 쓴다.

## 6. 검증

- 동치 IT 셋(`PlaceListSnapshotLoaderIT`·`PlaceListViewPatchEquivalenceIT`·
  `PlaceListSnapshotEquivalenceIT`)은 **단언을 바꾸지 않고** 통과해야 한다. 응답 불변의 증거다.
- `PlaceStatsRepositoryIT` — upsert가 네 컬럼을 채운다, MAIN이 둘이면 `place_tag.id` 작은 쪽,
  MAIN 없음·비활성 MAIN의 `main_tag_id`.
- `AdminPlaceUpdateSnapshotIT` — 이름만 고친 뒤 `place_stats.name`이 바뀌고 표시값 패치가 새
  이름을 싣는다.
- `place_stats`에 직접 INSERT하는 테스트 다섯 파일에 `name`을 더한다(NOT NULL).
- 전체 테스트 통과.

## 7. 범위 밖으로 남기는 것

- 캠페인 시딩 스크립트(`load-test/campaigns/2026-09-09_rebuild-scale/seed/make-scale-schema.sh`,
  미추적)의 `place_stats` INSERT는 새 컬럼을 모른다. 측정을 하지 않으므로 이번에 고치지 않고,
  다음 캠페인 때 맞춘다.
- 재빌드 소요는 로그로 남을 뿐 판정에 쓰지 않는다. JOIN 제거의 이득을 숫자로 말하려면 같은
  창 대조가 필요하고, 그것은 이 작업의 목표가 아니다.
