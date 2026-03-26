-- places: created_at은 LIMIT 없는 전체 조회에서 filesort 비용 절감 효과가 없으므로 제거
-- (town_id, active) 복합 인덱스로 축소
DROP INDEX idx_places_town_active_created ON places;
CREATE INDEX idx_places_town_active
    ON places (town_id, active);
