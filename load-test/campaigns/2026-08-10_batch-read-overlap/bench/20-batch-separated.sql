-- B-분리 팔(현행): 카운트 배치를 손으로 한 번 발화한다.
-- 두 문장은 PlaceStatsRepository#upsertCounts / #deleteStaleRows를 그대로 옮긴 것이다 —
-- :calculatedAt 자리만 @ts로 바뀌었고 별칭·조건·COALESCE 유무는 손대지 않았다.
--
-- ⚠️ READ COMMITTED가 아니면 INSERT ... SELECT가 bookmarks·place_reviews 전체에 shared next-key
--    락을 걸어 동시 INSERT를 ERROR 1205로 죽인다(근거는 upsertCounts javadoc).
-- ⚠️ UPSERT와 DELETE는 반드시 한 트랜잭션이다. 갈라지면 두 문장 사이에 회차가 섞인 구간이 생긴다.
-- ⚠️ @ts는 세션 변수라 이 파일 전체가 mysql 클라이언트 한 프로세스에 통째로 들어가야 한다.
--    두 문장에 같은 값이 가지 않으면 DELETE가 방금 적재한 행까지 전부 지운다.
--
-- 실행: docker exec -i solply-bench-mysql mysql -uroot -prootpwd solply_bench_db < 20-batch-separated.sql

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
SET @ts = NOW(6);

START TRANSACTION;

INSERT INTO place_stats (
    place_id, town_id, bookmark_count, review_count, avg_rating, count_calculated_at)
SELECT p.id,
       p.town_id,
       COALESCE(b.cnt, 0),
       COALESCE(r.cnt, 0),
       r.avg_rating,
       @ts
FROM places p
LEFT JOIN (
    SELECT bm.target_id AS place_id,
           COUNT(*) AS cnt
    FROM bookmarks bm
    WHERE bm.target_type = 'PLACE'
      AND bm.created_at <= @ts
    GROUP BY bm.target_id
) b ON b.place_id = p.id
LEFT JOIN (
    SELECT pr.place_id AS place_id,
           COUNT(*) AS cnt,
           AVG(pr.rating) AS avg_rating
    FROM place_reviews pr
    WHERE pr.created_at <= @ts
    GROUP BY pr.place_id
) r ON r.place_id = p.id
WHERE p.active = 1
ON DUPLICATE KEY UPDATE
    town_id             = VALUES(town_id),
    bookmark_count      = VALUES(bookmark_count),
    review_count        = VALUES(review_count),
    avg_rating          = VALUES(avg_rating),
    count_calculated_at = VALUES(count_calculated_at);

DELETE FROM place_stats
 WHERE count_calculated_at <> @ts;

COMMIT;
