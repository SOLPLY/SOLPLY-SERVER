-- 후보 α별 Top-50과 세 지표.
--
-- score(α) = α·LN(1 + bookmark_count) + momentum + review_term
-- 원본 재스캔은 없다 — 전부 hybrid_base(6,320행) 위의 산술이다.
--
-- 정렬은 `점수 DESC, place_id ASC`. 두 번째 키가 없으면 동점 시 순서가 실행마다 달라져
-- 겹침률이 흔들린다. 실제로 동점은 거의 없지만 재현성은 운에 맡기지 않는다.
--
-- α 목록은 하나의 테이블에서 온다. 강건성 확인(α*/2, α*×2)이 필요해지면
-- hybrid_alphas에 행을 추가하고 이 파일을 다시 돌리면 된다.

DROP TABLE IF EXISTS hybrid_rank;
CREATE TABLE hybrid_rank (
    target   VARCHAR(16) NOT NULL,
    alpha    DOUBLE      NOT NULL,
    place_id BIGINT      NOT NULL,
    rn       INT         NOT NULL,
    score    DOUBLE      NOT NULL,
    PRIMARY KEY (target, alpha, place_id),
    INDEX idx_rn (target, alpha, rn)
);

INSERT INTO hybrid_rank (target, alpha, place_id, rn, score)
SELECT target, alpha, place_id, rn, score FROM (
    SELECT tg.target, a.alpha, hb.place_id,
           a.alpha * LN(1 + hb.bookmark_count) + hb.momentum + hb.review_term AS score,
           ROW_NUMBER() OVER (
               PARTITION BY tg.target, a.alpha
               ORDER BY a.alpha * LN(1 + hb.bookmark_count) + hb.momentum + hb.review_term DESC,
                        hb.place_id ASC) AS rn
    FROM hybrid_targets tg
    JOIN hybrid_base hb ON hb.town_id = tg.town_id
    CROSS JOIN hybrid_alphas a
) x
WHERE rn <= 50;

-- 대상별 누적 북마크 상위 10 — 성공 조건 (i)과 문제 실재 게이트의 관측 대상
DROP TABLE IF EXISTS hybrid_top10bm;
CREATE TABLE hybrid_top10bm (
    target   VARCHAR(16) NOT NULL,
    place_id BIGINT      NOT NULL,
    rn       INT         NOT NULL,
    bookmark_count INT   NOT NULL,
    PRIMARY KEY (target, place_id)
);
INSERT INTO hybrid_top10bm (target, place_id, rn, bookmark_count)
SELECT target, place_id, rn, bookmark_count FROM (
    SELECT tg.target, hb.place_id, hb.bookmark_count,
           ROW_NUMBER() OVER (PARTITION BY tg.target
                              ORDER BY hb.bookmark_count DESC, hb.place_id ASC) AS rn
    FROM hybrid_targets tg
    JOIN hybrid_base hb ON hb.town_id = tg.town_id
) y
WHERE rn <= 10;

-- ── 지표 ────────────────────────────────────────────────────────────────
SELECT r.target,
       r.alpha,
       (SELECT COUNT(*) FROM hybrid_top10bm t
         WHERE t.target = r.target
           AND EXISTS (SELECT 1 FROM hybrid_rank r2
                        WHERE r2.target = r.target AND r2.alpha = r.alpha
                          AND r2.place_id = t.place_id))                AS top10_survive,
       (SELECT COUNT(*) FROM hybrid_rank r3
          JOIN hybrid_trending tr ON tr.place_id = r3.place_id
         WHERE r3.target = r.target AND r3.alpha = r.alpha)             AS trending_in_top50,
       (SELECT COUNT(*) FROM hybrid_rank r4
          JOIN hybrid_rank r0 ON r0.target = r4.target AND r0.alpha = 0
                             AND r0.place_id = r4.place_id
         WHERE r4.target = r.target AND r4.alpha = r.alpha)             AS overlap_with_a0
FROM (SELECT DISTINCT target, alpha FROM hybrid_rank) r
ORDER BY r.target, r.alpha;
