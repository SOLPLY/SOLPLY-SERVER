-- 보조 분석 — "게이트 미달"이 공식의 성질인가, 데이터의 무력함인가.
--
-- 사전 등록된 판정에는 없는 계산이다. 판정을 바꾸려는 것이 아니라, 결과 문서의
-- "이 측정으로 말할 수 없는 것"을 단언이 아니라 수치로 쓰기 위해 덧붙인다.
-- (원본 재스캔 없음 — 전부 hybrid_base 위의 산술이다)
--
-- ## 왜 필요한가
-- 문제 실재 게이트가 0/10으로 미달했다. 그런데 비트렌딩 5,980곳의 momentum/bookmark_count가
-- 0.294~0.424(σ=0.020)로 사실상 상수다. 즉 momentum ≈ 0.358 × bookmark_count이라
-- **두 항이 같은 양의 상수배**이고, 그러면 α를 아무리 키워도 순위가 갈릴 수 없다.
-- 시드가 각 장소의 유입률을 548일 창 내내 일정하게 뒀기 때문이다
-- (generate-bench-seed.mjs: 시각은 NOW − 548일 × u^1.6, 장소 가중치는 시간 불변).
-- 그래서 이 데이터에는 "과거에 사랑받았으나 지금 유입이 끊긴 장소"가 한 곳도 없다.
--
-- ## 무엇을 계산하는가
-- 대상별 누적 1위 장소 P가 Top-50에서 실제로 탈락하려면 유입이 얼마나 죽어야 하는가.
-- P가 Top-50에 있으므로 `P ∈ Top50 ⟺ score(P) > score(현재 51위)`가 정확히 성립한다.
--     ρ_crit = (S51(α) − α·ln(1+c_P) − rev_P) / c_P
-- 이것을 실제 모집단 비율(≈0.358)로 나눈 값이 "평시 유입의 몇 %까지 죽어야 탈락하는가"다.
-- 그 값이 음수면 **유입이 0이 되어도 탈락하지 않는다**는 뜻이다.

SELECT tg.target,
       a.alpha,
       p.place_id            AS top_place,
       p.bookmark_count      AS c_p,
       ROUND(p.momentum / p.bookmark_count, 4) AS rho_now,
       ROUND(s51.score, 1)   AS cutoff_51,
       ROUND((s51.score - a.alpha * LN(1 + p.bookmark_count) - p.review_term)
             / p.bookmark_count, 4)            AS rho_crit,
       ROUND(100 * (s51.score - a.alpha * LN(1 + p.bookmark_count) - p.review_term)
             / p.bookmark_count / (p.momentum / p.bookmark_count), 1) AS pct_of_normal_inflow
FROM (SELECT DISTINCT target FROM hybrid_targets) tg
CROSS JOIN hybrid_alphas a
JOIN hybrid_top10bm t ON t.target = tg.target AND t.rn = 1
JOIN hybrid_base p ON p.place_id = t.place_id
JOIN (
    -- 대상·α별 51위 점수
    SELECT target, alpha, score FROM (
        SELECT tg2.target, a2.alpha,
               a2.alpha * LN(1 + hb.bookmark_count) + hb.momentum + hb.review_term AS score,
               ROW_NUMBER() OVER (
                   PARTITION BY tg2.target, a2.alpha
                   ORDER BY a2.alpha * LN(1 + hb.bookmark_count) + hb.momentum + hb.review_term DESC,
                            hb.place_id ASC) AS rn
        FROM hybrid_targets tg2
        JOIN hybrid_base hb ON hb.town_id = tg2.town_id
        CROSS JOIN hybrid_alphas a2
    ) z WHERE rn = 51
) s51 ON s51.target = tg.target AND s51.alpha = a.alpha
ORDER BY tg.target, a.alpha;
