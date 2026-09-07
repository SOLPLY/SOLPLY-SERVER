-- bookmarks: user_id + target_type + created_at 복합 인덱스로 교체
-- 적용 쿼리 패턴: WHERE user_id = ? AND target_type = 'PLACE' [AND target_id = ?] [ORDER BY created_at DESC]
DROP INDEX idx_bookmark_user_created ON bookmarks;
CREATE INDEX idx_bookmark_user_type_created
    ON bookmarks (user_id, target_type, created_at DESC);

-- places: town_id + active + created_at 복합 인덱스로 교체
-- 적용 쿼리 패턴: WHERE town_id = ? AND active = true ORDER BY created_at DESC
-- 주의: idx_places_town_id는 FK 제약 조건에서 사용 중이므로 새 인덱스를 먼저 생성 후 삭제
CREATE INDEX idx_places_town_active_created
    ON places (town_id, active, created_at DESC);
DROP INDEX idx_places_town_id ON places;
