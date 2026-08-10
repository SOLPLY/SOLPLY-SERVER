#!/usr/bin/env bash
# 서울 전체 + 흔한 태그에서 "옵티마이저 선택(태그 주도)" 대 "지역 주도 강제"를 다시 잰다.
#
# 2026-08-06의 같은 비교는 V29/V30 스키마의 즉석 측정이었고 원자료가 남지 않았다.
# V32(place_stats 단일행)와 tags 조인 제거가 모두 들어간 현행 형상에서 다시 채취한다.
#
# JOIN_PREFIX는 **진단 도구**다. 코드에 넣지 않는다 — 결과 0건 태그에서 75배 손해가 나는 것을
# 2026-08-06에 확인했고, 이 스크립트는 그 판정을 뒤집으려는 것이 아니라 남은 격차의 크기만 본다.
#
# 서울 18곳 × 태그 조합 2가지 × 정렬 2가지 × 힌트 유무 2가지 = 8건. 각각 워밍업 1회 후 3회.
#
# 사용: tools/explain-hint-recheck.sh [라벨]      (기본 라벨: v32)
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:-v32}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
OUT="results/explain/$LABEL.txt"
mkdir -p results/explain

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw solply_bench_db -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t solply_bench_db -e "$1" 2>/dev/null; }

CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"

ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

# $1=towns $2=조건 $3=힌트 문자열(빈 값이면 없음)
pop() { echo "SELECT $3 ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT $3 p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
WHERE p.town_id IN ($1)
  AND p.active = 1
$2
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

COMBOS=(
  "흔한 태그 1개|= 1"
  "흔한 태그 3개|= 1|IN (7,8,9,10)|IN (11,12,13,14,15,16)"
)

timings() {
  local sql="$1" out=""
  q "$sql" >/dev/null || true
  for _ in 1 2 3; do
    out="$out $(q "EXPLAIN ANALYZE $sql" | head -1 \
        | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
  done
  echo "$out"
}

: > "$OUT"
{
  echo "================================================================"
  echo "# 라벨: $LABEL / 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# MySQL: $(q 'SELECT VERSION()')"
  echo "# 서울 18곳 = $CITY"
  echo "# 힌트없음 = 옵티마이저 선택 / 지역주도 = JOIN_PREFIX(ps 또는 p)"
  echo "# 스키마: V32 (place_stats 장소당 1행), place_tag 인덱스는 현행 (tag_id) 단일 컬럼"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회"
  echo "================================================================"
  qt "SELECT index_name, seq_in_index, column_name FROM information_schema.statistics
       WHERE table_schema='solply_bench_db' AND table_name='place_tag'
       ORDER BY index_name, seq_in_index;"
} >> "$OUT"

for combo in "${COMBOS[@]}"; do
  IFS='|' read -r cname c1 c2 c3 <<<"$combo"
  for sort_name in 인기순 최신순; do
    idcol="ps.place_id"; drv="ps"
    [ "$sort_name" = "최신순" ] && { idcol="p.id"; drv="p"; }
    conds="$(ex "$idcol" "$c1")"
    [ -n "${c2:-}" ] && conds="$conds
$(ex "$idcol" "$c2")"
    [ -n "${c3:-}" ] && conds="$conds
$(ex "$idcol" "$c3")"
    for arm in 힌트없음 지역주도; do
      hint=""; [ "$arm" = "지역주도" ] && hint="/*+ JOIN_PREFIX($drv) */"
      if [ "$sort_name" = "인기순" ]; then sql="$(pop "$CITY" "$conds" "$hint")"
      else sql="$(lat "$CITY" "$conds" "$hint")"; fi
      {
        echo "################################################################"
        echo "# [$arm] $sort_name · 서울 18곳 · $cname"
        echo "################################################################"
        echo "--- SQL ---"; echo "$sql"
        echo "--- 시간(3회, ms) ---"; timings "$sql"
        echo "--- EXPLAIN ANALYZE ---"; q "EXPLAIN ANALYZE $sql"
        echo
      } >> "$OUT"
    done
  done
done

echo "[explain] 저장: $OUT"
