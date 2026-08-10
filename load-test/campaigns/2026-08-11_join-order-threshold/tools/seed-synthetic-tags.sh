#!/usr/bin/env bash
# 조인 순서 전환점 탐색용 합성 태그를 벤치 DB에 깐다 (seed) / 지운다 (rollback).
#
# 왜 합성인가: 실태그의 연결 수 분포는 185와 1,146 사이가 비어 있다(223~731에 몰려 있고
# 그 사이 지점을 골라도 지리 분포가 제각각이라 규모만 다른 비교가 안 된다). 전환점을 재려면
# "규모만 다르고 나머지는 같은" 태그가 필요해서 만든다.
#
# 분포: 활성 장소를 id 순으로 세우고 Bresenham 방식으로 정확히 N개를 등간격으로 고른다.
#   FLOOR(rn*N/TOTAL) > FLOOR((rn-1)*N/TOTAL)  →  rn=1..TOTAL 구간에서 정확히 N행
# ORDER BY RAND()를 쓰지 않는 이유는 재현 불가능해서다. 같은 데이터에 다시 돌리면 같은 행이 뽑힌다.
# 한계: 실태그의 지역 편중(강남에 카페가 몰리는 식)은 재현하지 않는다 — 전국 균등이다.
#
# id 대역: 합성 태그는 9001~ 을 쓴다. 실태그 최대 id가 34라 충돌하지 않고,
#          롤백이 "tag_id >= 9001을 지운다" 한 줄로 끝난다.
#
# 사용: tools/seed-synthetic-tags.sh [seed|rollback|verify]      (기본: seed)
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-seed}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
SYNTH_MIN_ID=9001

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

# ---------- 합성 태그 정의 ----------
# id|type|parent_id|연결 장소 수|이름
# MAIN 7개는 주장 ①·③의 규모 스윕 축이다. 185와 1,146은 실태그(바/술집·카페)와 나란히 보려고 넣었다.
# OPTION1 3개는 주장 ②(메인 + 희귀 서브태그)의 서브 쪽이다. 실태그 서브가 전부 OPTION1/OPTION2에
# parent를 가지므로 형태를 맞춰 카페(1)의 자식으로 만든다 — SQL은 tags를 조인하지 않으므로
# 플랜에 영향은 없고, 데이터 형태만 실제와 같게 두는 것이다.
SYNTH=(
  "9001|MAIN|NULL|20|합성메인0020"
  "9002|MAIN|NULL|50|합성메인0050"
  "9003|MAIN|NULL|100|합성메인0100"
  "9004|MAIN|NULL|185|합성메인0185"
  "9005|MAIN|NULL|400|합성메인0400"
  "9006|MAIN|NULL|700|합성메인0700"
  "9007|MAIN|NULL|1146|합성메인1146"
  "9101|OPTION1|1|20|합성서브0020"
  "9102|OPTION1|1|100|합성서브0100"
  "9103|OPTION1|1|400|합성서브0400"
)

ensure_v33_shape() {
  local has_new has_old
  has_new=$(q "SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema='$DB' AND table_name='place_tag'
                  AND index_name='idx_place_tag_tag_place'" | tr -d '[:space:]')
  has_old=$(q "SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema='$DB' AND table_name='place_tag'
                  AND index_name='idx_place_tag_tag_id'" | tr -d '[:space:]')
  # fk_place_tag_tag가 tag_id 선두 인덱스를 요구한다 — V33과 같이 새것을 먼저 만들고 옛것을 지운다.
  [ "$has_new" = "0" ] && { echo "[seed] V33 형상 없음 → idx_place_tag_tag_place 생성" >&2
                            q "CREATE INDEX idx_place_tag_tag_place ON place_tag (tag_id, place_id)"; }
  [ "$has_old" != "0" ] && { echo "[seed] idx_place_tag_tag_id 제거 (V33)" >&2
                             q "DROP INDEX idx_place_tag_tag_id ON place_tag"; }
  true
}

rollback() {
  echo "[seed] 롤백: 합성 place_tag·tags 삭제" >&2
  q "DELETE FROM place_tag WHERE tag_id >= $SYNTH_MIN_ID"
  q "DELETE FROM tags WHERE id >= $SYNTH_MIN_ID"
  q "ANALYZE TABLE place_tag" >/dev/null
  q "ANALYZE TABLE tags" >/dev/null
}

verify() {
  qt "SELECT t.id, t.name, t.type, t.parent_id,
             COUNT(pt.id) AS linked,
             SUM(p.town_id = 301) AS in_town301,
             SUM(p.town_id BETWEEN 301 AND 318) AS in_city
        FROM tags t
        LEFT JOIN place_tag pt ON pt.tag_id = t.id
        LEFT JOIN places p ON p.id = pt.place_id
       WHERE t.id >= $SYNTH_MIN_ID
       GROUP BY t.id, t.name, t.type, t.parent_id
       ORDER BY t.id;"
  qt "SELECT COUNT(*) AS synth_tag_rows FROM tags WHERE id >= $SYNTH_MIN_ID;
      SELECT COUNT(*) AS synth_place_tag_rows FROM place_tag WHERE tag_id >= $SYNTH_MIN_ID;"
  # 중복 연결이 없어야 한다 (uk_place_tag_place_tag 제약이 막지만 실제로 0인지 확인한다)
  qt "SELECT COUNT(*) AS dup_pairs FROM (
        SELECT place_id, tag_id FROM place_tag WHERE tag_id >= $SYNTH_MIN_ID
        GROUP BY place_id, tag_id HAVING COUNT(*) > 1) d;"
}

case "$MODE" in
  rollback) rollback; verify; exit 0 ;;
  verify)   verify;   exit 0 ;;
  seed)     ;;
  *) echo "사용: $0 [seed|rollback|verify]" >&2; exit 2 ;;
esac

# ---------- 시딩 ----------
ensure_v33_shape

# 이미 깔려 있으면 먼저 지우고 다시 깐다 (중복 방지 + 멱등)
rollback

TOTAL=$(q "SELECT COUNT(*) FROM places WHERE active = 1" | tr -d '[:space:]')
echo "[seed] 활성 장소 TOTAL=$TOTAL" >&2

for row in "${SYNTH[@]}"; do
  IFS='|' read -r tid ttype tparent tsize tname <<<"$row"
  q "INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
     VALUES ($tid, '$tname', '$ttype', $tparent, 1, 'PLACE')"
  # Bresenham 등간격: rn=1..TOTAL 중 정확히 tsize개가 전 구간에 고르게 뽑힌다.
  q "INSERT INTO place_tag (place_id, tag_id)
     SELECT t.place_id, $tid FROM (
       SELECT id AS place_id, ROW_NUMBER() OVER (ORDER BY id) AS rn
         FROM places WHERE active = 1
     ) t
     WHERE FLOOR(t.rn * $tsize / $TOTAL) > FLOOR((t.rn - 1) * $tsize / $TOTAL)"
  echo "[seed] tag $tid ($tname) 목표 $tsize" >&2
done

q "ANALYZE TABLE tags" >/dev/null
q "ANALYZE TABLE place_tag" >/dev/null

echo "[seed] 검증"
verify
