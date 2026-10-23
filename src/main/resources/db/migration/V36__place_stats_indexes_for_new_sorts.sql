-- V36: 목록 정렬을 2종에서 6종으로 늘리면서, 정적 정렬 3종(평점·리뷰 수·북마크 수)의 인덱스를
-- 짓는다. 거리순은 기준점이 요청마다 달라 인덱스로 흡수할 수 없으므로 여기에 없다 — 그쪽은
-- 후보를 커버링으로 훑고 앱에서 정렬한다(PlaceService의 거리순 경로).
--
-- 세 인덱스 모두 V34가 세운 문법을 그대로 따른다: 선두가 (town_id, 정렬키...)로 필터·정렬을
-- 만들고, place_id를 **명시**해 타이브레이크 자리를 잡은 뒤, 말단에 표시 컬럼과 tag_bitmask를
-- 실어 커버링을 완성한다. place_id를 명시하는 이유는 V34와 같다 — 뒤에 표시 컬럼을 더 실어야
-- 하는데, 암묵 PK 접미사는 항상 맨 뒤로 가서 이 순서를 만들 수 없다.
--
-- ⚠️ SELECT나 WHERE에 place_stats 컬럼을 더할 때는 해당 인덱스 말단도 함께 늘린다. 덮지 못한
--    컬럼이 하나라도 있으면 행마다 PRIMARY(place_id) 룩업이 붙어 이 인덱스의 존재 이유가 사라진다
--    (V30·V32·V34 주석에 채택 근거).

-- ASC/DESC와 PK 접미사 — V34가 남긴 함정을 세 인덱스에 대해 다시 따진다.
--
-- 규칙은 하나다. "정렬키 DESC + place_id ASC"를 만들려면 정렬키를 **DESC로 선언**하고 place_id를
-- ASC로 두어 정방향 스캔이 그 순서를 그대로 내게 해야 한다(V34의 인기순 인덱스가 이 형태다).
-- 전부 ASC로 선언한 뒤 역방향 스캔에 기대면 place_id까지 함께 뒤집혀 DESC가 되므로 타이브레이크가
-- 어긋나고 filesort가 남는다 — V31·V34의 최신순이 반대 방향(생성일 DESC + id DESC)이라 일부러
-- 전부 ASC로 선언했던 것과 정확히 같은 이유에서, 여기서는 반대 선택이 옳다.
--
-- 아래 셋의 타이브레이크는 전부 place_id ASC다(인기순과 같은 규칙). 최신순만 id DESC인 것은
-- "생성일 DESC + id DESC"가 역방향 스캔 한 번에 나오기 때문이며, 그 예외를 여기로 옮기지 않는다.

-- 평점순 (avg_rating DESC, review_count DESC, place_id ASC).
--
-- 정렬 키가 둘인 유일한 정렬이다. 평점은 DECIMAL(3,2)이라 동점이 흔하고(4.50이 수두룩하다),
-- 동점을 곧장 id로 가르면 "평점 같고 리뷰 5,000개인 곳"과 "평점 같고 리뷰 1개인 곳"의 순서가
-- id 우연에 맡겨진다. 그래서 리뷰 수를 2단 키로 세운다 — 베이지안 보정으로 한 축에 합치는 안은
-- 채택하지 않았다. 보정 상수를 배치가 관리해야 하고, 그 상수가 갈리는 순간 발급된 커서의
-- 좌표계가 통째로 어긋난다.
--
-- ⚠️ 평점순은 avg_rating IS NOT NULL 행만 본다 — 인기순의 score_calculated_at 술어와 같은 성질의
--    선택이다. 리뷰가 없는 장소의 avg_rating은 NULL이고 그것은 "0점"이 아니라 "평점이 없다"이므로
--    평점 순서 위에 놓을 자리가 없다. 게다가 커서 seek 조건은 NULL과의 비교가 전부 NULL이라
--    첫 페이지 뒤로는 그 행들이 **조용히 사라진다** — 어차피 못 싣는다면 술어로 명시해 끊는 편이
--    정직하다. NULL은 DESC 선언 인덱스의 끝에 모여 있어 이 술어가 곧 범위 조건이 된다.
CREATE INDEX idx_place_stats_town_rating
    ON place_stats (town_id, avg_rating DESC, review_count DESC, place_id,
                    tag_bitmask, bookmark_count);

-- 리뷰 많은 순 (review_count DESC, place_id ASC). 말단이 태그 술어와 나머지 표시값 둘을 덮는다.
-- review_count는 NOT NULL이라 평점순 같은 NULL 문제가 없다 — 리뷰가 없으면 0이고, 0은 "리뷰가
-- 0개"라는 정확한 사실이라 맨 뒤에 놓이면 그만이다.
CREATE INDEX idx_place_stats_town_reviews
    ON place_stats (town_id, review_count DESC, place_id,
                    tag_bitmask, bookmark_count, avg_rating);

-- 북마크 많은 순 (bookmark_count DESC, place_id ASC). 인기순과 헷갈리지 말 것 — 인기 점수는
-- 시간 감쇠와 평점을 섞은 복합 점수이고, 이쪽은 누적 원값이다. 축이 다르므로 인덱스도 따로 선다.
CREATE INDEX idx_place_stats_town_bookmarks
    ON place_stats (town_id, bookmark_count DESC, place_id,
                    tag_bitmask, review_count, avg_rating);
