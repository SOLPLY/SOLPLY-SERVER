-- 배치 1회 — ln 명예 항을 추가한 버전. 성공 조건 3(배치 비용) 측정용.
--
-- 2026-07-31_place-stats-batch/bench/batch-round.sql과 **한 줄만 다르다**:
--     popular_score = COALESCE(b.score,0) + COALESCE(r.score,0)
--   → popular_score = @alpha * LN(1 + COALESCE(b.cnt,0)) + COALESCE(b.score,0) + COALESCE(r.score,0)
--
-- 추가 작업량이 장소 수(6,320)만큼의 LN 호출뿐이라는 점이 이 파일의 논점이다.
-- b.cnt는 현행 배치가 이미 bookmark_count로 쓰려고 계산해 두는 값이라 **새 스캔도 새 집계도 없다.**
-- 10,399,466행 스캔 옆에서 6,320회 LN이 보이는지를 재는 것이다.
--
-- 호출자가 @cat(기준 시각)과 @alpha를 세팅한다.
-- 쓰기 대상도 place_stats로 동일하게 둔다 — 인덱스·FK 갱신 비용을 빼면 공정한 비교가 아니다.

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;

SET @t0 = NOW(6);
START TRANSACTION;

INSERT INTO place_stats (
    place_id, town_id, active, popular_score,
    bookmark_count, review_count, avg_rating, calculated_at)
SELECT p.id,
       p.town_id,
       p.active,
       @alpha * LN(1 + COALESCE(b.cnt, 0)) + COALESCE(b.score, 0) + COALESCE(r.score, 0),
       COALESCE(b.cnt, 0),
       COALESCE(r.cnt, 0),
       r.avg_rating,
       @cat
FROM places p
LEFT JOIN (
    SELECT bm.target_id AS place_id,
           COUNT(*) AS cnt,
           SUM(1.0 * POW(0.5,
               TIMESTAMPDIFF(SECOND, bm.created_at, @cat) / 86400.0 / 90.0)) AS score
    FROM bookmarks bm
    WHERE bm.target_type = 'PLACE'
      AND bm.created_at <= @cat
    GROUP BY bm.target_id
) b ON b.place_id = p.id
LEFT JOIN (
    SELECT pr.place_id AS place_id,
           COUNT(*) AS cnt,
           AVG(pr.rating) AS avg_rating,
           SUM(3.0 * (pr.rating - 3) * POW(0.5,
               TIMESTAMPDIFF(SECOND, pr.created_at, @cat) / 86400.0 / 90.0)) AS score
    FROM place_reviews pr
    WHERE pr.created_at <= @cat
    GROUP BY pr.place_id
) r ON r.place_id = p.id
ON DUPLICATE KEY UPDATE
    town_id        = VALUES(town_id),
    active         = VALUES(active),
    popular_score  = VALUES(popular_score),
    bookmark_count = VALUES(bookmark_count),
    review_count   = VALUES(review_count),
    avg_rating     = VALUES(avg_rating),
    calculated_at  = VALUES(calculated_at);

COMMIT;
SELECT TIMESTAMPDIFF(MICROSECOND, @t0, NOW(6)) / 1000.0 AS batch_ms;
