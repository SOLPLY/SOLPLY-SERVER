# 2026-09-09 · 스냅샷 원천을 place_stats 한 테이블로 — 이름·좌표·메인 태그·썸네일의 비정규화

> **지위: #401 브랜치 위에 얹는 확정 설계다.** 별도 이슈 없이 진행한다(사용자 결정, 2026-09-09).
> 성공 조건은 측정이 아니라 구조다 — 재빌드 문장 ①이 `place_stats` 단독이 되고, 동치 IT가
> 단언을 바꾸지 않고 통과하며, 응답이 바뀌지 않는 것. 재빌드 소요는 기록만 하고 판정에 쓰지 않는다.
>
> **2026-09-09 같은 날 썸네일 키까지 범위에 더함(사용자 지시).** 처음에는 "안 옮기는 것"에 두었던
> 값이다. 옮기고 나니 로더에서 문장 하나(썸네일 전량)가 통째로 사라져, 이 문서의 목표가
> "조인 없애기"에서 "로더가 읽는 테이블을 하나로"로 한 걸음 더 갔다.

## 1. 출발점 — "조인이 없다"가 반쪽만 참이었다

V34의 요점은 목록 조회가 `place_stats` 하나로 끝난다는 것이었다. 그 말은 DB 경로의 정렬 넷
(인기·최신·평점·카운트)에 대해 정확하다. 예외가 둘 있었다.

- **거리순 DB 경로**는 좌표 때문에 `places`와 JOIN한다. 좌표가 `places`에만 있어서다.
- **스냅샷 로더의 문장 ①**은 `places`(이름·좌표)와 MAIN 태그 파생 테이블(`place_tag ⋈ tags
  WHERE type='MAIN'`) 둘을 JOIN한다. DB 경로는 결과 행의 id로 엔티티를 따로 읽어 표시값을
  채우므로 목록 쿼리에 이름이 필요 없었지만, 스냅샷은 재빌드 한 번에 표시값까지 담아야 한다.

그리고 로더에는 예외가 하나 더 있었다. **문장 ②(썸네일)**는 `place_images` 전량을 따로 읽어
장소 → 파일 키 맵으로 접었다. 장소당 여러 행이라 문장 ①에 접히지 않는다는 이유였다.

이 문서는 둘째 예외와 문장 ②를 함께 없앤다. `place_stats`에 없던 네 값 — 이름·좌표·메인 태그 id·
썸네일 파일 키 — 를 옮겨 문장 ①을 `SELECT … FROM place_stats ps` 단독으로 만든다.

## 2. 무엇을 옮기고 무엇을 안 옮기나

| 값 | 어디서 왔나 | 옮긴 뒤 | 쓰기 경로 변화 |
|---|---|---|---|
| `name` | `places.name` | `place_stats.name` NOT NULL | **이름만 고친 수정도 upsert를 부른다** (§4) |
| `latitude`·`longitude` | `places` | `place_stats` NULL 허용 | 없음 — 좌표는 이미 소속 열쇠라 upsert가 돌던 경로 |
| `main_tag_id` | 파생 테이블 | `place_stats.main_tag_id` NULL 허용 | 없음 — 태그 변경은 집합이 바뀌어 upsert가 돌던 경로 |
| `thumbnail_file_key` | `place_images`를 `display_order, image_file_key` 순으로 정렬한 첫 행 | `place_stats.thumbnail_file_key` TEXT NULL 허용 | **이미지 이동 후처리(`PlaceImageFieldUpdater`)도 upsert를 부른다** (§5) |

썸네일 칸이 TEXT인 것은 `place_images.image_file_key`가 TEXT라서다. 원값을 그대로 담는 칸이라
타입을 좁히면 긴 키가 잘려 죽은 URL이 된다. NULL과 빈 문자열은 다른 상태로 남긴다 —
"이미지가 없다"와 "첫 이미지의 키가 비어 있다"는 다르고, 후자에서 다음 이미지로 넘어가면
엔티티 경로(`Place#getThumbnailFileKey`)와 값이 갈린다.

안 옮기는 것과 이유:

- **태그 전량(문장 ③)** — 이 문서의 범위 밖이다. 태그는 장소에 딸린 값이 아니라 별도 사전이고,
  수십 행이라 접어 둘 값어치도 없다.
- **DB 경로 거리순의 `places` JOIN** — 좌표가 옮겨지면 뗄 수 있지만 DB 경로는 2단계 철거
  예정(#397 후속)이라 손대지 않는다. PK 룩업 JOIN이라 계획이 흔들릴 자유도도 없다.
- **인덱스** — 다섯 컬럼 모두 정렬·필터 축이 아니다. 로더는 전량 테이블 스캔이라 커버링이 의미
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
다섯 컬럼을 더한다. 카운트·점수 칸은 여전히 건드리지 않는다. 메인 태그는 파생 테이블로 뽑는다:

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

썸네일은 상관 서브쿼리다:

```sql
(SELECT pi.image_file_key
   FROM place_images pi
  WHERE pi.place_id = p.id
  ORDER BY pi.display_order, pi.image_file_key
  LIMIT 1)
```

"첫 MAIN = `place_tag.id` 오름차순 첫 행, 활성 무관"과 "썸네일 = `display_order`, `image_file_key`
순의 첫 행"이라는 두 규칙을 이 형태 그대로 V40 백필·upsert·rebuild 세 곳이 쓴다. SQL이라 공유할 수
없어 복사되지만, 규칙의 검증은 `PlaceStatsRepositoryIT` 한 곳(upsert 결과)에 둔다.

### #401 §4-3의 기각과 어긋나지 않는 이유

`docs/design/2026-09-09-rebuild-streaming.md` §4-3은 "문장 ①에 상관 스칼라 서브쿼리를 붙여 대표
이미지 한 건만 받는다"를 측정으로 기각했다(x100에서 문장 ① 대비 +2,280ms). 같은 모양의 서브쿼리를
여기서 쓰는 것이 모순처럼 보이지만, 기각된 것은 **서브쿼리 자체가 아니라 그것이 도는 빈도**다.

- §4-3의 자리: **재빌드마다**. 10분 주기 × 전 장소이므로 장소마다 한 번 도는 비용이 회차마다
  전부 반복된다. 그 대가로 아끼는 것은 문장 ②가 더 보내는 행 10%뿐이었다(장소당 이미지 평균 1.1건).
- 이번 자리: **어드민 쓰기 한 건**(PK IN 소수 행)과 **기동·복구 전량**, 그리고 **V40 백필 한 번**.
  전자는 빈도가 불규칙한 소수 요청이고 후자 둘은 일회성이다. 그리고 얻는 것이 다르다 — 아끼는 것이
  전송 10%가 아니라 **재빌드에서 문장 하나가 통째로 사라지는 것**이다.

바꿔 말하면 §4-3은 "이 계산을 읽기 시점에 두지 말라"고 했고, 이번 변경은 그 계산을 **쓰기 시점으로
옮겼다**. 그 절의 결론(재빌드에 서브쿼리를 붙이지 않는다)은 지금도 그대로 참이다.

`AdminPlaceService#updatePlace`는 소속 열쇠가 같아도 upsert를 부른다. 이름이 `place_stats`의
칸이 된 순간 "표시값만 바뀐 수정은 파생 컬럼이 그대로"라는 전제가 깨졌기 때문이다. 그 뒤의
분기(패치냐 전량 재빌드냐)는 그대로다 — 열쇠가 같으면 표시값 패치, 다르면 재빌드.

## 5. 읽기 경로 — 문장 ②가 사라져 로더는 두 문장

문장 ①에서 JOIN 둘과 `ORDER BY ps.place_id`, 그리고 직전 id 비교로 하던 중복 스킵을 지운다.
장소당 한 행은 PK가 보장하므로 인접성에 기댈 이유가 없다.

**문장 ②(썸네일 전량)는 통째로 사라진다.** 딸려 사라지는 것이 셋이다 — `place_images` 전량 스캔,
장소 → 파일 키 `HashMap`(재빌드 동안 살아 있던 중간 자료구조), 그리고 "썸네일 문장을 닫은 뒤에
장소 문장을 연다"는 streaming 순서 계약의 절반. 이제 로더가 여는 것은 문장 ①(streaming)과
문장 ③(태그 전량) 둘뿐이고, 문장 번호 ③은 옛 이름 그대로 둔다 — 다른 문서·주석이 그 번호로
태그 문장을 가리킨다.

단건 문장(`SINGLE_VIEW_SQL`)은 `FROM place_stats ps WHERE ps.place_id = :placeId`로 바꿔
`ps.name`·`ps.main_tag_id`·`ps.thumbnail_file_key`를 읽는다. 전량과 단건이 **같은 원천**을 읽어야
두 경로의 동치가 성립한다. 썸네일에서는 이것이 특히 크다 — 전까지 그 규칙은 전량(정렬 + 자바에서
첫 행)과 단건(상관 서브쿼리) 두 벌로 복사돼 있었고, 두 벌이 어긋나도 각자는 그럴듯한 답을 냈다.
지금은 규칙이 쓰기 문장 한 곳에만 있다.

부작용 하나 — 비활성 장소는 행이 없어 패치가 no-op이 된다. 지금 javadoc은
"없음과 비활성을 구분해야 패치가 조용히 죽지 않는다"고 적었지만, 비활성 장소는 정렬 배열에
없어 화면에 닿지 않고 재활성은 전량 재빌드를 부르므로 그 구분에 값이 없다. javadoc을 이
근거로 바꿔 쓴다.

### 비동기 이미지 경로의 순서

S3 이동·복사가 끝난 뒤 `place_images`의 키를 스테이징에서 최종으로 갈아 끼우는 경로
(`PlaceImageFieldUpdater#replaceImages`)는 어드민 트랜잭션이 커밋된 **뒤에** 비동기로 돈다.
여기서 순서가 하나 늘었다:

```
이미지 키 교체 → upsertRowsForActivePlaces(placeId) → patchPlaceViewAfterCommit(placeId)
```

**upsert가 패치보다 먼저여야 한다.** 패치가 읽는 원천이 `place_stats.thumbnail_file_key`이므로,
순서가 뒤집히면 패치는 방금 갈아 끼운 최종 키가 아니라 스테이징 키를 다시 싣는다 — 값은
그럴듯하고 URL만 죽어 있어 아무 오류도 나지 않는 종류의 버그다. 엔티티 변경이 upsert 문장보다
먼저 flush되는 것은 `@Modifying(flushAutomatically = true)`가 보장한다. 순서 자체는
`PlaceImageFieldUpdaterTest`가 `InOrder`로 못 박는다.

## 6. 검증

- 동치 IT 셋(`PlaceListSnapshotLoaderIT`·`PlaceListViewPatchEquivalenceIT`·
  `PlaceListSnapshotEquivalenceIT`)은 **단언을 바꾸지 않고** 통과해야 한다. 응답 불변의 증거다.
- `PlaceStatsRepositoryIT` — upsert가 다섯 컬럼을 채운다, MAIN이 둘이면 `place_tag.id` 작은 쪽,
  MAIN 없음·비활성 MAIN의 `main_tag_id`, 썸네일의 `display_order` 순·값 타이브레이커·
  이미지 없음(NULL)·빈 키(빈 문자열).
- `AdminPlaceUpdateSnapshotIT` — 이름만 고친 뒤 `place_stats.name`이 바뀌고 표시값 패치가 새
  이름을 싣는다.
- `place_stats`에 직접 INSERT하는 테스트 다섯 파일에 `name`을 더한다(NOT NULL).
  `thumbnail_file_key`는 NULL 허용이라 그 픽스처들을 다시 손대지 않는다.
- **픽스처가 `place_images`를 직접 INSERT하는 자리에는 규칙을 SQL로 복사하지 않는다.**
  `PlaceListViewPatchEquivalenceIT`가 그 자리인데, 운영 문장 `upsertRowsForActivePlaces`를
  트랜잭션으로 감싸 부르는 헬퍼(`resyncStats`)로 place_stats 칸을 채운다 — `PlaceListFlowIT`이
  이름·좌표 때 만든 것과 같은 수법이다.
- 전체 테스트 통과.

## 7. 범위 밖으로 남기는 것

- 캠페인 시딩 스크립트(`load-test/campaigns/2026-09-09_rebuild-scale/seed/make-scale-schema.sh`,
  미추적)의 `place_stats` INSERT는 새 컬럼을 모른다. 측정을 하지 않으므로 이번에 고치지 않고,
  다음 캠페인 때 맞춘다.
- 재빌드 소요는 로그로 남을 뿐 판정에 쓰지 않는다. JOIN 제거의 이득을 숫자로 말하려면 같은
  창 대조가 필요하고, 그것은 이 작업의 목표가 아니다.
