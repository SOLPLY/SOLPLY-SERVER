-- 합성 태그(id 40 규모 20 · id 41 0건) 롤백.
--
-- ⚠️ A 채취 직후에는 실행하지 않는다 — B 채취가 같은 태그를 쓴다 (비트 자리 = tag id ≤ 62).
--    B까지 끝난 뒤 한 번만 돌리고, 아래 잔재 확인 두 줄이 모두 0인지 본다.
--
-- 실행:
--   docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd \
--     --default-character-set=utf8mb4 -t solply_bench_db \
--     < load-test/campaigns/2026-08-11_tag-bitmask-read-model/bench/rollback-synthetic-tags.sql

DELETE FROM place_tag WHERE tag_id IN (40, 41);
DELETE FROM tags      WHERE id     IN (40, 41);

-- ⚠️ B 채취 이후에는 tags·place_tag를 지우는 것만으로 롤백이 끝나지 않는다.
-- V34가 place_stats.tag_bitmask에 태그 소속을 비정규화해 두었으므로, 위 두 DELETE는
-- **마스크에 선 40·41번 비트를 건드리지 않는다** — 그대로 두면 이제 존재하지도 않는 태그의
-- 자리가 20개 행에 남아, 나중에 tag id 40·41이 재사용될 때 조용히 오탐이 된다.
-- 해당 두 자리만 끈다 (다른 태그의 자리는 손대지 않는다).
UPDATE place_stats
   SET tag_bitmask = tag_bitmask & ~((1 << 40) | (1 << 41))
 WHERE (tag_bitmask & ((1 << 40) | (1 << 41))) != 0;

ANALYZE TABLE place_tag;
ANALYZE TABLE tags;
ANALYZE TABLE place_stats;

-- 잔재 확인 — 넷 다 0이어야 한다
SELECT COUNT(*) AS leftover_tags      FROM tags      WHERE id     IN (40, 41);
SELECT COUNT(*) AS leftover_place_tag FROM place_tag WHERE tag_id IN (40, 41);
SELECT COUNT(*) AS leftover_bit40     FROM place_stats WHERE (tag_bitmask & (1 << 40)) != 0;
SELECT COUNT(*) AS leftover_bit41     FROM place_stats WHERE (tag_bitmask & (1 << 41)) != 0;

-- 마스크가 place_tag 재계산값과 일치하는지 — 0이어야 한다 (비트 끄기가 재계산과 같은 결과인지 확인)
SELECT COUNT(*) AS mask_mismatch FROM place_stats ps
  LEFT JOIN (SELECT place_id, BIT_OR(1 << tag_id) AS m FROM place_tag GROUP BY place_id) t
         ON t.place_id = ps.place_id
 WHERE ps.tag_bitmask <> COALESCE(t.m, 0);
