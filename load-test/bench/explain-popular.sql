-- v0 직행 쿼리 실행계획 (타겟 축 인덱스 검증)
-- 기대: 상관 서브쿼리가 idx_bookmark_target ref + Using index (커버링)
EXPLAIN
SELECT x.id, x.cnt FROM (
    SELECT p.id AS id,
           (SELECT COUNT(*) FROM bookmarks b
             WHERE b.target_type = 'PLACE' AND b.target_id = p.id) AS cnt
    FROM places p
    WHERE p.town_id IN (102,103,104,105,106,107,108,109,110,111)
      AND p.active = 1
) x
ORDER BY x.cnt DESC, x.id ASC LIMIT 20;

-- 스냅샷 로더 집계 쿼리 실행계획
-- 기대: range scan on idx_bookmark_target, Using index
EXPLAIN
SELECT b.target_id, COUNT(*) AS cnt
FROM bookmarks b
WHERE b.target_type = 'PLACE'
  AND b.target_id IN (10001,10002,10003,10004,10005)
GROUP BY b.target_id;

-- 인덱스 없을 때와의 비교가 필요하면:
-- ALTER TABLE bookmarks DROP INDEX idx_bookmark_target;  → 위 EXPLAIN 재실행 → 재생성
