-- V34: place_stats를 목록 조회 두 정렬(인기순·최신순)의 단일 읽기 모델로 완성한다.
--
-- 지금까지 최신순의 기준 테이블은 places였고, 태그 필터는 두 정렬 모두 place_tag EXISTS 세 개로
-- 나갔다. 그 조인이 남아 있는 한 옵티마이저에게 조인 순서·세미조인 전략의 자유도가 있고, LIMIT
-- 조기 종료를 비용에 세지 못하는 맹점 탓에 입력(태그 규모 × 지역 크기)에 따라 계획이 흔들렸다.
-- 조건부 힌트(JOIN_PREFIX + SEMIJOIN(FIRSTMATCH))로 그 맹점을 우회해 봤으나, 임계가 "지역 후보
-- ≈1,800"이라는 요청 형상 한 점에서만 유효함이 실측으로 확정돼 철회했다
-- (load-test/campaigns/2026-08-11_region-size-threshold).
--
-- 이 마이그레이션은 그 선택지 자체를 없앤다 — 태그 소속을 place_stats의 비트마스크 컬럼으로
-- 비정규화해 조인을 술어로 바꾸고, 최신순의 정렬 축(created_at)까지 같은 테이블로 들여온다.
-- 남는 계획은 "ps 커버링 인덱스 스캔 + 비트 필터" 하나뿐이라 입력이 형태를 바꾸지 못한다.
--
-- ⚠️ 비트 자리 = tag id다. BIGINT의 쓸 수 있는 자리는 0..62(63은 부호 비트)이므로 **tag id가 62를
--    넘으면 이 모델이 성립하지 않는다.** 지금 최대 id는 34(V2 시드)이고, 그 위로 새 태그가 생기는
--    것을 AdminTagService가 거부한다. 62를 넘겨야 할 날이 오면 마스크를 BINARY(n)로 넓히거나
--    태그 소속을 다시 조인으로 되돌리는 결정을 여기서 다시 해야 한다 — 조용히 넘어갈 수 없게
--    TagBitmask 유틸도 같은 상한에서 예외를 던진다.

-- created_at은 백필 대상이라 NULL로 열고 채운 뒤 NOT NULL로 조인다. NOT NULL로 바로 추가하면
-- 기존 행이 암묵 기본값(제로 날짜)을 받아 strict 모드에서 거부되거나, 통과하더라도 "장소 생성일"이
-- 아닌 값이 조용히 들어앉는다.
ALTER TABLE place_stats
    ADD COLUMN created_at  DATETIME NULL COMMENT '장소 생성일. places.created_at의 비정규화 사본',
    ADD COLUMN tag_bitmask BIGINT   NOT NULL DEFAULT 0 COMMENT '이 장소가 가진 태그의 비트 합집합. 비트 자리 = tag id (0..62)';

-- ⚠️ 여기의 백필은 V32가 금지한 백필과 성질이 다르다. V32 주석이 막은 것은 bookmarks·place_reviews
-- **전량 집계**로, Flyway의 REPEATABLE READ 트랜잭션이 그 두 테이블에 shared next-key 락을 걸어
-- 동시 북마크 INSERT를 ERROR 1205로 죽이는 경로였다. 아래 둘은 읽는 쪽이 places·place_tag이고,
-- 둘 다 어드민만 쓰는 저빈도 테이블이라 사용자 트래픽과 충돌하지 않는다. 그리고 두 컬럼은
-- "다음 배치가 채우면 된다"로 미룰 수 없다 — created_at이 NOT NULL이고, 마스크가 0인 채로
-- 서빙되면 태그 필터 결과가 배치 회차까지 통째로 빈다.
UPDATE place_stats ps
    JOIN places p ON p.id = ps.place_id
SET ps.created_at = p.created_at;

UPDATE place_stats ps
    JOIN (SELECT pt.place_id, BIT_OR(1 << pt.tag_id) AS mask
          FROM place_tag pt
          GROUP BY pt.place_id) t ON t.place_id = ps.place_id
SET ps.tag_bitmask = t.mask;

ALTER TABLE place_stats
    MODIFY COLUMN created_at DATETIME NOT NULL COMMENT '장소 생성일. places.created_at의 비정규화 사본';

-- 인기순 인덱스는 V32의 것에 tag_bitmask를 **말단에** 더한 형태다. 앞의 세 컬럼이 필터·정렬·
-- 타이브레이크를 만들고 나머지가 커버링을 만드는 구조는 그대로다. 마스크가 말단인 이유는
-- score_calculated_at과 같다 — 탐색 범위를 좁히지 않고 range 안에서 행마다 평가되는 필터라
-- 순서상 뒤에 와야 하고, 그럼에도 인덱스에 실려 있어야 걸러낼 행마다 PRIMARY 룩업이 붙지 않는다.
DROP INDEX idx_place_stats_town_score ON place_stats;
CREATE INDEX idx_place_stats_town_score
    ON place_stats (town_id, popular_score DESC, place_id,
                    bookmark_count, review_count, avg_rating, score_calculated_at, tag_bitmask);

-- 최신순 인덱스 신설. 이 인덱스가 생기면서 최신순의 기준 테이블이 places → place_stats로 바뀐다.
--
-- ⚠️ created_at을 ASC로 선언한다 — DESC로 두면 오히려 어긋난다. 세컨더리 인덱스 뒤에는 PK가
--    오름차순으로 붙고 여기 PK는 place_id이므로,
--      ASC  형태 → 역방향 스캔이 created_at DESC, place_id DESC (ORDER BY와 정확히 일치)
--      DESC 형태 → created_at DESC, place_id ASC  (타이브레이크가 어긋나 filesort가 남는다)
--    같은 수법을 places에 적용한 V31에 실측 근거가 있다(0.030ms vs 0.059ms).
--    place_id를 가운데에 명시하는 것은 뒤에 표시 컬럼을 더 실어야 하기 때문이다. 명시하지 않으면
--    암묵 PK 접미사는 항상 맨 뒤로 가서 이 순서를 만들 수 없다.
--
-- 말단 네 컬럼(tag_bitmask, bookmark_count, review_count, avg_rating)이 최신순 SELECT와 태그
-- 술어를 전부 덮어 커버링을 만든다. score_calculated_at은 없다 — 최신순은 미채점 신규 장소를
-- 보여야 하므로 그 술어를 걸지 않는다.
CREATE INDEX idx_place_stats_town_created
    ON place_stats (town_id, created_at, place_id,
                    tag_bitmask, bookmark_count, review_count, avg_rating);
