-- 기반 집계 — 원본(북마크 1,039만 · 리뷰 19.8만)을 딱 한 번만 스캔한다.
--
-- 이후 모든 후보 α의 점수는 이 표 위의 산술 조합이므로 재스캔이 필요 없다:
--     score(α) = α·LN(1 + bookmark_count) + momentum + review_term
--
-- 두 서브쿼리는 PlaceStatsRepository.upsertAll(= bench/batch-round.sql)의 본문을 그대로 옮기고
-- 파라미터만 상수로 치환한 것이다. 새로 짜지 않는다 — 공식이 어긋나면 측정 전체가 무효다.
--   :calculatedAt → @calc,  :bookmarkWeight → 1.0,  :reviewWeight → 3.0,  :halfLifeDays → 90
--   created_at <= @calc 상한도 그대로 유지한다 (없으면 감쇠가 아니라 증폭이 된다)
--
-- @calc은 고정 상수다. NOW()를 쓰면 실행 시각마다 점수가 달라져 α 후보 간 비교가 재현되지 않는다.
-- 시드의 최신 북마크가 2026-07-30 06:49이므로 그보다 뒤인 값을 쓴다.

SET @calc := TIMESTAMP('2026-08-01 02:00:00');

DROP TABLE IF EXISTS hybrid_base;

CREATE TABLE hybrid_base AS
SELECT p.id                AS place_id,
       p.town_id           AS town_id,
       COALESCE(b.cnt, 0)  AS bookmark_count,   -- 누적 총량 (감쇠 없음)
       COALESCE(b.score, 0) AS momentum,        -- Σ 1.0 × 0.5^(경과일/90)
       COALESCE(r.score, 0) AS review_term      -- Σ 3.0 × (rating−3) × 0.5^(경과일/90)
FROM places p
LEFT JOIN (
    SELECT bm.target_id AS place_id,
           COUNT(*) AS cnt,
           SUM(1.0 * POW(0.5,
               TIMESTAMPDIFF(SECOND, bm.created_at, @calc) / 86400.0 / 90.0)) AS score
    FROM bookmarks bm
    WHERE bm.target_type = 'PLACE'
      AND bm.created_at <= @calc
    GROUP BY bm.target_id
) b ON b.place_id = p.id
LEFT JOIN (
    SELECT pr.place_id AS place_id,
           SUM(3.0 * (pr.rating - 3) * POW(0.5,
               TIMESTAMPDIFF(SECOND, pr.created_at, @calc) / 86400.0 / 90.0)) AS score
    FROM place_reviews pr
    WHERE pr.created_at <= @calc
    GROUP BY pr.place_id
) r ON r.place_id = p.id;

ALTER TABLE hybrid_base ADD PRIMARY KEY (place_id), ADD INDEX idx_town (town_id);

-- 검증 1: 현행 배치가 계산해 둔 place_stats와 α=0 점수가 일치하는가.
-- 기준 시각이 다르므로(place_stats는 배치 시각, 여기는 @calc) 값 자체는 다르다.
-- 대신 α=0 순위가 place_stats 순위와 같은지를 본다 — 다르면 서브쿼리를 잘못 옮긴 것이다.
SELECT COUNT(*) AS places, SUM(bookmark_count) AS total_bookmarks,
       ROUND(SUM(momentum), 2) AS sum_momentum, ROUND(SUM(review_term), 2) AS sum_review
FROM hybrid_base;
