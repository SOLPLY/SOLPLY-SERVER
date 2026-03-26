-- places: created_at은 LIMIT 없는 전체 조회에서 filesort 비용 절감 효과가 없으므로 제거
-- (town_id, active) 복합 인덱스로 축소
DROP INDEX idx_places_town_active_created ON places;
CREATE INDEX idx_places_town_active
    ON places (town_id, active);

-- bookmarks: created_at은 backfill 쿼리에 ORDER BY가 없으므로 불필요
-- target_id가 인덱스에 없어 커버링 인덱스도 아님
-- (user_id, target_type) 복합 인덱스로 축소
DROP INDEX idx_bookmark_user_type_created ON bookmarks;
CREATE INDEX idx_bookmark_user_type
    ON bookmarks (user_id, target_type);
