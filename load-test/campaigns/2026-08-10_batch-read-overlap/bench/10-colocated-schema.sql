-- A-결합 팔 전용 스키마: 통계가 place_stats로 분리되지 않고 places에 눌러앉은 세계를 흉내낸다.
-- 컬럼 다섯의 타입은 V32의 place_stats 정의를 그대로 옮긴 것이다(행폭·더티 페이지 수 비교의 전제).
--
-- ⚠️ Flyway 밖 수동 적용이다. 벤치 DB에만 넣고 90-colocated-rollback.sql로 되돌린다 —
--    이력에 남기면 제품 스키마가 벤치 흉내 컬럼을 물려받는다.
--
-- 실행: docker exec -i solply-bench-mysql mysql -uroot -prootpwd solply_bench_db < 10-colocated-schema.sql

ALTER TABLE places
    ADD COLUMN bench_bookmark_count      INT            NOT NULL DEFAULT 0,
    ADD COLUMN bench_review_count        INT            NOT NULL DEFAULT 0,
    ADD COLUMN bench_avg_rating          DECIMAL(3, 2)  NULL,
    ADD COLUMN bench_popular_score       DECIMAL(18, 6) NOT NULL DEFAULT 0,
    ADD COLUMN bench_count_calculated_at DATETIME(6)    NULL;

-- V32의 idx_place_stats_town_score를 places에 미러링한 것이다. 서빙 경로는 고정이라 이 인덱스를
-- 읽지 않지만, 결합 설계였다면 랭킹 커버링 인덱스도 여기 있었을 테니 배치의 인덱스 재작성 비용까지
-- A팔에 얹는 것이 목적이다. 그 몫이 빠지면 A팔이 결합 비용을 과소평가한다.
-- 본과 다른 점 둘: 타이브레이크가 place_id가 아니라 places의 PK인 id이고, 말단
-- score_calculated_at은 없다(점수 배치는 이 캠페인의 비교 대상이 아니다).
CREATE INDEX idx_places_bench_town_score
    ON places (town_id, bench_popular_score DESC, id,
               bench_bookmark_count, bench_review_count, bench_avg_rating);
