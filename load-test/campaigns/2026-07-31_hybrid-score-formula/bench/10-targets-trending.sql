-- 평가 대상 4곳과 트렌딩 20곳을 작업 테이블로 고정한다.
--
-- ## 대상
-- 북마크 상위 leaf 3곳 + 서울 시 단위(leaf 301~318 합집합). 실측 상위:
--   301 강남 709,152 / 310 홍대·합정 625,119 / 306 건대·성수 584,501 (4위 311 연남·망원 451,843)
-- 서울 leaf는 각 100곳씩 총 1,800곳이다.
--
-- ## 트렌딩 20곳 — 왜 목록을 "복원"해야 했는가
-- generate-bench-seed.mjs는 가중치 상위 200 중 20곳을 시드 없는 Math.random()으로 뽑고
-- 그 id를 stderr로만 출력한다. 그 로그가 남아 있지 않으므로 데이터에서 되찾는다.
--
-- 판별 조건은 주입 로직 자체에서 나온다 (mjs 165~172행):
--   트렌딩: 북마크의 45%를 최근 7일에 몰아 준다 → 최근 7일 비중 ≈ 0.45 + 0.55×0.066 ≈ 0.486
--   일반  : created_at = NOW − 548일 × u^1.6 → 최근 7일 비중 = (7/548)^0.625 ≈ 0.066
-- 실측 분포는 예측대로 완전히 갈렸다 (경계가 임의로 그어지지 않았다는 근거):
--   비중 0.477 ~ 0.495 : 20곳   ← 트렌딩
--   비중 0.124 이하    : 6,300곳
-- 사이가 텅 비어 있으므로 임계값을 0.3 어디에 두든 같은 20곳이 나온다.

SET @win := TIMESTAMP('2026-07-23 06:49:50');   -- 시드 생성 시각(최신 북마크 2026-07-30 06:49:50) − 7일

DROP TABLE IF EXISTS hybrid_targets;
CREATE TABLE hybrid_targets (target VARCHAR(16) NOT NULL, town_id INT NOT NULL,
                             INDEX idx_town (town_id));
INSERT INTO hybrid_targets (target, town_id) VALUES
    ('gangnam', 301), ('hongdae', 310), ('konkuk', 306);
INSERT INTO hybrid_targets (target, town_id)
SELECT 'seoul', id FROM towns WHERE parent_id = 201;   -- 301~318

DROP TABLE IF EXISTS hybrid_trending;
CREATE TABLE hybrid_trending AS
SELECT place_id, total, recent7d, recent7d / total AS frac
FROM (
    SELECT bm.target_id AS place_id, COUNT(*) AS total,
           SUM(bm.created_at >= @win) AS recent7d
    FROM bookmarks bm
    WHERE bm.target_type = 'PLACE'
    GROUP BY bm.target_id
) f
WHERE recent7d / total > 0.30;
ALTER TABLE hybrid_trending ADD PRIMARY KEY (place_id);

-- 20곳이 아니면 판별 조건이 깨진 것이다. 그대로 진행하지 말 것.
SELECT COUNT(*) AS trending_places, ROUND(MIN(frac), 4) AS min_frac, ROUND(MAX(frac), 4) AS max_frac
FROM hybrid_trending;

-- 대상별 트렌딩 소속 수 — "트렌딩 진입 수"의 분모다
SELECT tg.target, COUNT(DISTINCT tr.place_id) AS trending_in_target
FROM hybrid_targets tg
JOIN hybrid_base hb ON hb.town_id = tg.town_id
JOIN hybrid_trending tr ON tr.place_id = hb.place_id
GROUP BY tg.target ORDER BY tg.target;

-- 트렌딩 목록 (결과 문서에 기록)
SELECT GROUP_CONCAT(place_id ORDER BY place_id) AS trending_ids FROM hybrid_trending;
