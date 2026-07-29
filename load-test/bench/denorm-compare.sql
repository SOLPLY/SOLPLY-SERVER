-- [v1' 비교 실험 전용] 배치 비정규화: places.bookmark_count 컬럼 + 주기 재계산
-- 증분 방식은 드리프트 리스크로 기각됨 — 이 방식은 배치 재계산이 본체라 드리프트가 없다 (설계 합의)

-- 1) 컬럼 준비 (멱등)
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'places' AND COLUMN_NAME = 'bookmark_count');
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE places ADD COLUMN bookmark_count BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'places' AND INDEX_NAME = 'idx_places_town_bookmark');
SET @ddl2 := IF(@idx_exists = 0,
  'CREATE INDEX idx_places_town_bookmark ON places (town_id, bookmark_count DESC)', 'SELECT 1');
PREPARE s2 FROM @ddl2; EXECUTE s2; DEALLOCATE PREPARE s2;

-- 2) 배치 재계산 (이 UPDATE의 소요 시간이 "배치 비용" 측정값)
UPDATE places p
LEFT JOIN (
    SELECT target_id, COUNT(*) AS cnt FROM bookmarks
    WHERE target_type = 'PLACE' GROUP BY target_id
) bc ON bc.target_id = p.id
SET p.bookmark_count = COALESCE(bc.cnt, 0);

-- 3) 비정규화 컬럼 기반 조회 (v1'의 "읽기 비용" — v0 상관 서브쿼리와 EXPLAIN·시간 비교)
EXPLAIN
SELECT p.id, p.bookmark_count
FROM places p
WHERE p.town_id IN (102,103,104,105,106,107,108,109,110,111) AND p.active = 1
ORDER BY p.bookmark_count DESC, p.id ASC LIMIT 20;

SELECT p.id, p.bookmark_count
FROM places p
WHERE p.town_id IN (102,103,104,105,106,107,108,109,110,111) AND p.active = 1
ORDER BY p.bookmark_count DESC, p.id ASC LIMIT 20;

-- 4) 실험 후 원복(선택): 앱 코드는 이 컬럼을 모르므로 남아 있어도 무해
-- ALTER TABLE places DROP INDEX idx_places_town_bookmark;
-- ALTER TABLE places DROP COLUMN bookmark_count;
