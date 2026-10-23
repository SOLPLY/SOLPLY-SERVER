-- 최신순 목록 조회의 정렬 축을 인덱스에 되돌린다.
--
-- V10이 (town_id, active, created_at DESC)를 만들었고 V11이 "LIMIT 없는 전체 조회에서 filesort
-- 비용 절감 효과가 없다"며 created_at을 뺐다. 그 판단은 **커서 페이징 도입 전**의 것이다.
-- 지금 최신순은 ORDER BY p.created_at DESC, p.id DESC + LIMIT 11로 나가는데, 정렬 축이 인덱스에
-- 없어 조회 범위의 활성 장소를 전부 읽어 정렬한다. 커서를 줘도 술어가 range 경계로 흡수되지 못해
-- 읽는 양이 줄지 않는다 (실측: docs/perf/2026-08-06-place-list-latest-explain.md §4.1~4.2).
--
-- ⚠️ created_at을 ASC로 둔다 — V10의 DESC 형태를 그대로 되살리면 안 된다.
--    세컨더리 인덱스 뒤에는 PK(id)가 오름차순으로 붙는다. 그래서
--      DESC 형태 → created_at DESC, id ASC  (정방향) → 타이브레이크가 어긋나 filesort가 남는다
--      ASC  형태 → created_at DESC, id DESC (역방향) → ORDER BY와 정확히 일치한다
--    실측으로 갈렸다: 동네 1곳 첫 페이지가 ASC 0.030ms(Backward index scan, 정렬 없음) vs
--    DESC 0.059ms(Using filesort 잔존). 근거는 docs/perf/2026-08-06-latest-sort-index.md.
--
-- SELECT가 places에서 만지는 컬럼(id·created_at·town_id·active)을 모두 덮으므로 커버링이 된다.
-- 다중 town 조회는 town별로만 순서가 만들어져 filesort가 남지만, 행 복원이 사라져 3배 싸다.
--
-- 주의: town_id FK 제약이 인덱스를 요구하므로 새 인덱스를 먼저 만들고 옛 것을 지운다 (V10·V11 동일).
--       (town_id, active)는 새 인덱스의 프리픽스라 idx_places_town_active는 중복이 된다.
CREATE INDEX idx_places_town_active_created
    ON places (town_id, active, created_at);
DROP INDEX idx_places_town_active ON places;
