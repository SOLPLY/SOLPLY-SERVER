#!/usr/bin/env bash
# A/B 공용 합성 태그를 벤치 DB에 깐다 (seed) / 확인한다 (verify).
#
# 직전 캠페인(2026-08-11_join-order-threshold)의 seed-synthetic-tags.sh와 분포 방식은 같고
# **id 대역만 다르다**. 9001~ 을 쓰지 않는 이유: B 형상의 비트 자리 = tag id라 62 이하여야 하고,
# A와 B가 같은 태그를 써야 비교가 성립한다 (캠페인 README §4 그리드 절).
#   id 40 — MAIN, 활성 장소 20곳에 연결 (전국 균등, Bresenham 등간격)
#   id 41 — MAIN, place_tag 연결 0건 (tags 행만 만든다)
# 실태그 최대 id가 34라 40·41은 비어 있고, 62 이하라 B의 비트 자리로 그대로 쓸 수 있다.
#
# ⚠️ 이 스크립트는 tags·place_tag만 건드린다. place_stats·places에 DDL을 하지 않는다.
# ⚠️ A 채취 후 롤백하지 않는다 — B 채취가 같은 태그를 쓴다. 롤백은 bench/rollback-synthetic-tags.sql.
#
# 사용: bench/seed-synthetic-tags.sh [seed|verify]      (기본: seed)
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-seed}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

verify() {
  qt "SELECT t.id, t.name, t.type, t.active,
             COUNT(pt.id) AS linked,
             SUM(p.town_id = 301) AS in_town301,
             SUM(p.town_id BETWEEN 301 AND 304) AS in_town4,
             SUM(p.town_id BETWEEN 301 AND 318) AS in_city18
        FROM tags t
        LEFT JOIN place_tag pt ON pt.tag_id = t.id
        LEFT JOIN places p ON p.id = pt.place_id
       WHERE t.id IN (40, 41)
       GROUP BY t.id, t.name, t.type, t.active
       ORDER BY t.id;"
  qt "SELECT COUNT(*) AS synth_tag_rows FROM tags WHERE id IN (40,41);
      SELECT COUNT(*) AS synth_place_tag_rows FROM place_tag WHERE tag_id IN (40,41);"
}

[ "$MODE" = "verify" ] && { verify; exit 0; }
[ "$MODE" = "seed" ] || { echo "사용: $0 [seed|verify]" >&2; exit 2; }

# 멱등: 이미 있으면 지우고 다시 깐다
q "DELETE FROM place_tag WHERE tag_id IN (40,41)"
q "DELETE FROM tags WHERE id IN (40,41)"

TOTAL=$(q "SELECT COUNT(*) FROM places WHERE active = 1" | tr -d '[:space:]')
echo "[seed] 활성 장소 TOTAL=$TOTAL" >&2

q "INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
   VALUES (40, '합성메인0020', 'MAIN', NULL, 1, 'PLACE')"
# Bresenham 등간격: rn=1..TOTAL 중 정확히 20개가 전 구간에 고르게 뽑힌다 (재현 가능 — RAND() 아님)
q "INSERT INTO place_tag (place_id, tag_id)
   SELECT t.place_id, 40 FROM (
     SELECT id AS place_id, ROW_NUMBER() OVER (ORDER BY id) AS rn
       FROM places WHERE active = 1
   ) t
   WHERE FLOOR(t.rn * 20 / $TOTAL) > FLOOR((t.rn - 1) * 20 / $TOTAL)"

q "INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
   VALUES (41, '합성메인0000', 'MAIN', NULL, 1, 'PLACE')"
# id 41은 place_tag 연결을 만들지 않는다 — 0건 태그가 이 칸의 측정 대상이다

q "ANALYZE TABLE tags" >/dev/null
q "ANALYZE TABLE place_tag" >/dev/null

echo "[seed] 검증"
verify
