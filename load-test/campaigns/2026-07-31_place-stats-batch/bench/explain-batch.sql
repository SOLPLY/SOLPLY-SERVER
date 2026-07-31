-- 항목 4 — 배치 집계가 인덱스 전용 스캔을 유지하는가
--
-- 검증 대상: `created_at <= :calculatedAt` 상한(커밋 b6a27e4)이 추가된 뒤에도
--   · 북마크 축이 V23의 idx_bookmark_target (target_type, target_id, created_at)로 Using index인가
--   · 리뷰 축이 V22의 idx_place_reviews_place_created_rating (place_id, created_at, rating)로 Using index인가
--
-- 이것이 이 캠페인에서 이론으로 못 푸는 두 항목 중 하나다. 옵티마이저의 인덱스 선택은
-- 카디널리티 통계에 의존하고, 상한이 붙었을 때 커버링이 유지될지는 "그럴 것"이지 확정이 아니다.
-- Extra에서 Using index가 빠지면 배치가 행마다 PK 룩업을 하게 되므로 V23 정의를 재검토해야 한다.
--
-- calculatedAt은 고정값을 쓴다. NOW()면 실행마다 계획 비교가 흔들린다.

SELECT '=== A1. 북마크 축 — 상한 있음 (현행) ===' AS ``;
EXPLAIN
SELECT bm.target_id AS place_id,
       COUNT(*) AS cnt,
       SUM(1.0 * POW(0.5, TIMESTAMPDIFF(SECOND, bm.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
FROM bookmarks bm
WHERE bm.target_type = 'PLACE'
  AND bm.created_at <= '2026-07-31 02:00:00'
GROUP BY bm.target_id;

SELECT '=== A2. 북마크 축 — 상한 없음 (대조: 상한이 계획을 바꿨는가) ===' AS ``;
EXPLAIN
SELECT bm.target_id AS place_id,
       COUNT(*) AS cnt,
       SUM(1.0 * POW(0.5, TIMESTAMPDIFF(SECOND, bm.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
FROM bookmarks bm
WHERE bm.target_type = 'PLACE'
GROUP BY bm.target_id;

SELECT '=== B1. 리뷰 축 — 상한 있음 (현행) ===' AS ``;
EXPLAIN
SELECT pr.place_id,
       COUNT(*) AS cnt,
       AVG(pr.rating) AS avg_rating,
       SUM(3.0 * (pr.rating - 3) * POW(0.5, TIMESTAMPDIFF(SECOND, pr.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
FROM place_reviews pr
WHERE pr.created_at <= '2026-07-31 02:00:00'
GROUP BY pr.place_id;

SELECT '=== C. 배치 전문(UPSERT의 SELECT 부분) 전체 계획 ===' AS ``;
EXPLAIN
SELECT p.id, p.town_id, p.active,
       COALESCE(b.score, 0) + COALESCE(r.score, 0),
       COALESCE(b.cnt, 0), COALESCE(r.cnt, 0), r.avg_rating,
       '2026-07-31 02:00:00'
FROM places p
LEFT JOIN (
    SELECT bm.target_id AS place_id, COUNT(*) AS cnt,
           SUM(1.0 * POW(0.5, TIMESTAMPDIFF(SECOND, bm.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
    FROM bookmarks bm
    WHERE bm.target_type = 'PLACE' AND bm.created_at <= '2026-07-31 02:00:00'
    GROUP BY bm.target_id
) b ON b.place_id = p.id
LEFT JOIN (
    SELECT pr.place_id AS place_id, COUNT(*) AS cnt, AVG(pr.rating) AS avg_rating,
           SUM(3.0 * (pr.rating - 3) * POW(0.5, TIMESTAMPDIFF(SECOND, pr.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
    FROM place_reviews pr
    WHERE pr.created_at <= '2026-07-31 02:00:00'
    GROUP BY pr.place_id
) r ON r.place_id = p.id;

SELECT '=== D. 실제 실행 계측 (EXPLAIN ANALYZE) — 북마크 축 ===' AS ``;
EXPLAIN ANALYZE
SELECT bm.target_id AS place_id, COUNT(*) AS cnt,
       SUM(1.0 * POW(0.5, TIMESTAMPDIFF(SECOND, bm.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
FROM bookmarks bm
WHERE bm.target_type = 'PLACE' AND bm.created_at <= '2026-07-31 02:00:00'
GROUP BY bm.target_id;

SELECT '=== E. 실제 실행 계측 — 리뷰 축 ===' AS ``;
EXPLAIN ANALYZE
SELECT pr.place_id, COUNT(*) AS cnt, AVG(pr.rating) AS avg_rating,
       SUM(3.0 * (pr.rating - 3) * POW(0.5, TIMESTAMPDIFF(SECOND, pr.created_at, '2026-07-31 02:00:00') / 86400.0 / 90)) AS score
FROM place_reviews pr
WHERE pr.created_at <= '2026-07-31 02:00:00'
GROUP BY pr.place_id;

SELECT '=== F. 디스크 임시 테이블로 밀렸는가 (설계 §2.6 주장) ===' AS ``;
SHOW GLOBAL STATUS LIKE 'Created_tmp_disk_tables';
SHOW GLOBAL STATUS LIKE 'Created_tmp_tables';
