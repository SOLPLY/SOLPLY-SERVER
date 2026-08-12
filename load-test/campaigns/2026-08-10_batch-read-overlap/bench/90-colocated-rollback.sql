-- 10-colocated-schema.sql의 역. 캠페인 종료 시 벤치 DB를 제품 스키마와 같은 형상으로 되돌린다.
-- 인덱스를 먼저 지운다 — 컬럼이 먼저 빠지면 인덱스가 함께 사라져 DROP INDEX가 ERROR 1091로 죽는다.
--
-- 실행: docker exec -i solply-bench-mysql mysql -uroot -prootpwd solply_bench_db < 90-colocated-rollback.sql

DROP INDEX idx_places_bench_town_score ON places;

ALTER TABLE places
    DROP COLUMN bench_count_calculated_at,
    DROP COLUMN bench_popular_score,
    DROP COLUMN bench_avg_rating,
    DROP COLUMN bench_review_count,
    DROP COLUMN bench_bookmark_count;
