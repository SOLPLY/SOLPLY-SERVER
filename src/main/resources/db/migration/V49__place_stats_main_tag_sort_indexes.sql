-- 메인 태그 미선택은 기존 (town_id, 정렬 키, place_id, ...) 인덱스를 유지한다.
-- 선택 시 (town_id, main_tag_id)를 동등 조건으로 좁히고 같은 정렬 순서를 읽는다.
-- tag_bitmask는 메인 태그까지 포함한 원래 표현을 유지한다.
-- main_tag_id는 V40에 이미 있으며 어드민 원본 갱신과 같은 트랜잭션에서 재계산된다.
--
-- 기존 main_tag_id는 첫 MAIN 태그를 고르는 표시값이었다. 한 장소에 서로 다른 MAIN이
-- 여러 개면 등호 조건으로 전환할 때 결과가 누락되므로 임의로 첫 태그만 남기지 않는다.
-- 해당 데이터는 원본에서 정리한 후 마이그레이션을 다시 적용해야 한다.
DROP PROCEDURE IF EXISTS validate_place_main_tags_v49;
DELIMITER $$
CREATE PROCEDURE validate_place_main_tags_v49()
BEGIN
    IF EXISTS (
        SELECT pt.place_id
        FROM place_tag pt
        JOIN tags t ON t.id = pt.tag_id
        JOIN place_stats ps ON ps.place_id = pt.place_id
        WHERE t.type = 'MAIN'
        GROUP BY pt.place_id
        HAVING COUNT(DISTINCT pt.tag_id) > 1
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V49: a listed place has multiple MAIN tags; reconcile source data first';
    END IF;
END$$
DELIMITER ;
CALL validate_place_main_tags_v49();
DROP PROCEDURE validate_place_main_tags_v49;

-- 현재 원본으로 갱신한다. MAIN이 없는 장소는 NULL이고, 태그 미선택 시 계속 조회된다.
UPDATE place_stats ps
LEFT JOIN (
    SELECT pt.place_id, MIN(pt.tag_id) AS main_tag_id
    FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
    WHERE t.type = 'MAIN'
    GROUP BY pt.place_id
) m ON m.place_id = ps.place_id
SET ps.main_tag_id = m.main_tag_id;

CREATE INDEX idx_place_stats_town_main_score
    ON place_stats (town_id, main_tag_id, popular_score DESC, place_id,
                    bookmark_count, review_count, avg_rating, score_calculated_at, tag_bitmask);
CREATE INDEX idx_place_stats_town_main_created
    ON place_stats (town_id, main_tag_id, created_at, place_id,
                    tag_bitmask, bookmark_count, review_count, avg_rating);
CREATE INDEX idx_place_stats_town_main_rating
    ON place_stats (town_id, main_tag_id, avg_rating DESC, review_count DESC, place_id,
                    tag_bitmask, bookmark_count);
CREATE INDEX idx_place_stats_town_main_reviews
    ON place_stats (town_id, main_tag_id, review_count DESC, place_id,
                    tag_bitmask, bookmark_count, avg_rating);
CREATE INDEX idx_place_stats_town_main_bookmarks
    ON place_stats (town_id, main_tag_id, bookmark_count DESC, place_id,
                    tag_bitmask, review_count, avg_rating);
