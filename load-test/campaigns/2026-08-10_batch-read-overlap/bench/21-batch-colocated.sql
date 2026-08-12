-- A-결합 팔: 20-batch-separated.sql과 **같은 집계**를 places 더미 컬럼에 착지시킨다.
-- 두 팔의 차이는 착지점 하나여야 하므로 집계 서브쿼리 둘(북마크·리뷰)과 @ts 상한은 글자 그대로 같다.
-- avg_rating에 COALESCE를 걸지 않는 것도 본과 같다 — 리뷰가 없으면 NULL이 정답이다.
--
-- ⚠️ 선행 조건: 10-colocated-schema.sql이 적용돼 있어야 한다.
-- ⚠️ bench_count_calculated_at = @ts가 매 발화마다 값을 바꾸므로 전 행이 실제로 쓰인다 —
--    B팔의 count_calculated_at 갱신과 대칭이다. 이게 없으면 카운트가 그대로인 행을 InnoDB가
--    no-op으로 흘려 A팔의 더티 페이지만 부당하게 적게 나온다.
-- ⚠️ 점수는 건드리지 않는다. 이 캠페인이 비교하는 것은 카운트 배치 한 회차다.
--
-- 실행: docker exec -i solply-bench-mysql mysql -uroot -prootpwd solply_bench_db < 21-batch-colocated.sql

SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
SET @ts = NOW(6);

START TRANSACTION;

UPDATE places p
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
SET p.bench_bookmark_count      = COALESCE(b.cnt, 0),
    p.bench_review_count        = COALESCE(r.cnt, 0),
    p.bench_avg_rating          = r.avg_rating,
    p.bench_count_calculated_at = @ts
WHERE p.active = 1;

COMMIT;
