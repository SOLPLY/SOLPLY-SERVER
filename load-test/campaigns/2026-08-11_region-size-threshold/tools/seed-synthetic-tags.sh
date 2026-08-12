#!/usr/bin/env bash
# 지역 크기 축 임계 스윕용 합성 태그를 벤치 DB에 깐다 (seed) / 지운다 (rollback).
#
# 직전 캠페인(2026-08-11_join-order-threshold)의 같은 이름 스크립트를 본으로 삼았고
# 분포 방식·id 대역·롤백 규약은 그대로다. 바뀐 것은 태그 목록뿐이다.
#
# 왜 이 목록인가: 이번 축은 태그 규모가 아니라 **지역 후보 수 R**이다. R 6점
#   {300, 600, 1000, 1800, 3600, 6000} 각각에 비율 격자 {0.15 .22 .30 .40 .52 .64 .80}
#   (R ≤ 1000에는 1.0 추가)를 곱한 태그 크기가 필요하다. 45개 (R, 비율) 조합이지만
#   크기가 겹치는 칸이 있어(예: 0.30×1800 = 0.15×3600 = 540) **같은 크기는 태그 하나로 공유**한다.
#   유니크 크기 40개 = 태그 40개. 공유해도 문제가 없는 이유는 태그가 전국 균등이라
#   "어느 R에서 쓰느냐"가 태그 자체의 성질을 바꾸지 않기 때문이다.
#
# 분포: 활성 장소를 id 순으로 세우고 Bresenham 방식으로 정확히 N개를 등간격으로 고른다.
#   FLOOR(rn*N/TOTAL) > FLOOR((rn-1)*N/TOTAL)  →  rn=1..TOTAL 구간에서 정확히 N행
# ORDER BY RAND()를 쓰지 않는 이유는 재현 불가능해서다. 같은 데이터에 다시 돌리면 같은 행이 뽑힌다.
# 한계: 실태그의 지역 편중(강남에 카페가 몰리는 식)은 재현하지 않는다 — 전국 균등이다.
#   그래서 R을 동네 몇 개로 만들든 태그의 기대 겹침은 R/TOTAL 비례로 균일하다.
#
# id 대역: 합성 태그는 9001~ 을 쓴다. 실태그 최대 id가 34라 충돌하지 않고,
#          롤백이 "tag_id >= 9001을 지운다" 한 줄로 끝난다.
#          id는 **크기 오름차순**으로 9001부터 부여한다(9001 = 가장 작은 크기).
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

# ---------- 격자 정의 (스윕 스크립트와 글자 그대로 같아야 한다) ----------
REGIONS=(300 600 1000 1800 3600 6000)
RATIOS_BASE=(0.15 0.22 0.30 0.40 0.52 0.64 0.80)
RATIO_EXTRA=1.00          # R <= 1000 에만 추가

# "R ratio size" 줄을 표준출력으로 낸다.
grid_rows() {
  local r ratio ratios
  for r in "${REGIONS[@]}"; do
    ratios=("${RATIOS_BASE[@]}")
    [ "$r" -le 1000 ] && ratios+=("$RATIO_EXTRA")
    for ratio in "${ratios[@]}"; do
      awk -v r="$r" -v x="$ratio" 'BEGIN{printf "%d %s %d\n", r, x, int(r*x+0.5)}'
    done
  done
}

# 유니크 크기 오름차순 (= tag id 9001+index 의 순서)
uniq_sizes() { grid_rows | awk '{print $3}' | sort -n -u; }

ensure_v33_shape() {
  local has_new has_old
  has_new=$(q "SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema='$DB' AND table_name='place_tag'
                  AND index_name='idx_place_tag_tag_place'" | tr -d '[:space:]')
  has_old=$(q "SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema='$DB' AND table_name='place_tag'
                  AND index_name='idx_place_tag_tag_id'" | tr -d '[:space:]')
  # fk_place_tag_tag가 tag_id 선두 인덱스를 요구한다 — V33과 같이 새것을 먼저 만들고 옛것을 지운다.
  # 이번 캠페인은 이미 V33 형상이라 아래 두 줄은 **발동하면 안 된다**. 발동하면 보고 대상이다.
  [ "$has_new" = "0" ] && { echo "[seed] ⚠️ V33 형상 없음 → idx_place_tag_tag_place 생성" >&2
                            q "CREATE INDEX idx_place_tag_tag_place ON place_tag (tag_id, place_id)"; }
  [ "$has_old" != "0" ] && { echo "[seed] ⚠️ idx_place_tag_tag_id 제거 (V33)" >&2
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
             CAST(SUBSTRING(t.name, 5) AS UNSIGNED) AS target,
             COUNT(pt.id) - CAST(SUBSTRING(t.name, 5) AS UNSIGNED) AS diff,
             SUM(p.town_id BETWEEN 301 AND 303) AS in_r300,
             SUM(p.town_id BETWEEN 301 AND 318) AS in_r1800,
             SUM(p.town_id BETWEEN 301 AND 360) AS in_r6000
        FROM tags t
        LEFT JOIN place_tag pt ON pt.tag_id = t.id
        LEFT JOIN places p ON p.id = pt.place_id
       WHERE t.id >= $SYNTH_MIN_ID
       GROUP BY t.id, t.name, t.type, t.parent_id
       ORDER BY t.id;"
  qt "SELECT COUNT(*) AS synth_tag_rows FROM tags WHERE id >= $SYNTH_MIN_ID;
      SELECT COUNT(*) AS synth_place_tag_rows FROM place_tag WHERE tag_id >= $SYNTH_MIN_ID;"
  # 목표와 어긋난 태그가 있으면 여기서 행이 나온다 (0행이어야 정상)
  qt "SELECT t.id, t.name, COUNT(pt.id) AS linked
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id = t.id
       WHERE t.id >= $SYNTH_MIN_ID
       GROUP BY t.id, t.name
      HAVING linked <> CAST(SUBSTRING(t.name, 5) AS UNSIGNED);"
  # 중복 연결이 없어야 한다 (uk_place_tag_place_tag 제약이 막지만 실제로 0인지 확인한다)
  qt "SELECT COUNT(*) AS dup_pairs FROM (
        SELECT place_id, tag_id FROM place_tag WHERE tag_id >= $SYNTH_MIN_ID
        GROUP BY place_id, tag_id HAVING COUNT(*) > 1) d;"
}

case "$MODE" in
  rollback) rollback; verify; exit 0 ;;
  verify)   verify;   exit 0 ;;
  seed)     ;;
  grid)     grid_rows; echo "--- uniq ---"; uniq_sizes | nl -ba -v"$SYNTH_MIN_ID"; exit 0 ;;
  *) echo "사용: $0 [seed|rollback|verify|grid]" >&2; exit 2 ;;
esac

# ---------- 시딩 ----------
ensure_v33_shape

# 이미 깔려 있으면 먼저 지우고 다시 깐다 (중복 방지 + 멱등)
rollback

TOTAL=$(q "SELECT COUNT(*) FROM places WHERE active = 1" | tr -d '[:space:]')
echo "[seed] 활성 장소 TOTAL=$TOTAL" >&2

# 크기 목록을 먼저 배열로 받는다 — while read 루프 안에서 docker exec -i를 부르면
# 그 프로세스가 루프의 stdin(격자 스트림)을 통째로 삼켜 첫 항목만 처리되고 끝난다.
SIZES=()
while read -r s; do [ -n "$s" ] && SIZES+=("$s"); done < <(uniq_sizes)

tid=$SYNTH_MIN_ID
for tsize in "${SIZES[@]}"; do
  tname="$(printf '합성지역%04d' "$tsize")"
  q "INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
     VALUES ($tid, '$tname', 'MAIN', NULL, 1, 'PLACE')"
  # Bresenham 등간격: rn=1..TOTAL 중 정확히 tsize개가 전 구간에 고르게 뽑힌다.
  q "INSERT INTO place_tag (place_id, tag_id)
     SELECT t.place_id, $tid FROM (
       SELECT id AS place_id, ROW_NUMBER() OVER (ORDER BY id) AS rn
         FROM places WHERE active = 1
     ) t
     WHERE FLOOR(t.rn * $tsize / $TOTAL) > FLOOR((t.rn - 1) * $tsize / $TOTAL)"
  echo "[seed] tag $tid ($tname) 목표 $tsize" >&2
  tid=$((tid + 1))
done

q "ANALYZE TABLE tags" >/dev/null
q "ANALYZE TABLE place_tag" >/dev/null

echo "[seed] 검증"
verify
