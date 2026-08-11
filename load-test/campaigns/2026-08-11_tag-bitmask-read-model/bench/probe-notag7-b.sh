#!/usr/bin/env bash
# S5(무태그) 칸만 B에서 7회로 다시 잰다 — **B 단독 보충 측정**이다.
#
# 왜: 3회 채취에서 town1/popular/notag가 0.0542;0.0553;0.0256으로 흔들려 중앙값이 A(0.0262)의
# 2배로 찍혔다. 그 칸은 0.03ms 대역이라 3회로는 노이즈와 회귀가 구분되지 않는다.
#
# ⚠️ A 쪽 짝은 없다. A는 마이그레이션 **전** 스키마에서만 잴 수 있는데 V34가 이미 적용돼 있어
#    지금 A를 다시 재면 다른 것을 재게 된다. 그러므로 이 파일은 "B가 자기 안에서 안정적인가"만
#    말하고, A와의 대소는 summary-a.csv의 3회 중앙값과 비교해 **보조로만** 읽어야 한다.
# ⚠️ 합성 태그 롤백 이후에 돌려도 무해하다 — 무태그 문장은 place_tag를 아예 읽지 않는다.
#
# 사용: bench/probe-notag7-b.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/probe-notag7-b.csv"
RUNS=7
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }

TOWN1="301"
TOWN4="301,302,303,304"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
declare -A REGION_TOWNS=([town1]="$TOWN1" [town4]="$TOWN4" [city18]="$CITY18")

pop() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT ps.place_id, ps.created_at, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
ORDER BY ps.created_at DESC, ps.place_id DESC LIMIT 11"; }

first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }

: > "$CSV"
echo "form,sort,region,tagcell,page,runs,driving_tables,driving_rows,times_ms,min_ms,median_ms,max_ms" >> "$CSV"

for region in town1 town4 city18; do
  for sort_name in popular latest; do
    towns="${REGION_TOWNS[$region]}"
    if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns")"; else sql="$(lat "$towns")"; fi

    f="$OUTDIR/b-${region}-notag-${sort_name}-r7.txt"
    {
      echo "################################################################"
      echo "# B 보충 채취 7회 (S5 무태그) · $sort_name · $region · p1"
      echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')  — 합성 태그 롤백 이후"
      echo "################################################################"
      echo "--- SQL ---"; echo "$sql"
    } > "$f"

    q "$sql" >/dev/null || true
    times=(); drvs=(); drows=()
    for run in $(seq 1 $RUNS); do
      tree="$(q "EXPLAIN ANALYZE $sql")"
      { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
      t="$(printf '%s\n' "$tree" | head -1 \
            | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
      d="$(driving_of "$tree" || true)"; dr="$(driving_rows "$tree" || true)"
      times+=("${t:-NA}"); drvs+=("${d:-none}"); drows+=("${dr:-NA}")
    done

    sorted=$(printf '%s\n' "${times[@]}" | sort -g)
    mn=$(printf '%s\n' "$sorted" | head -1)
    mx=$(printf '%s\n' "$sorted" | tail -1)
    md=$(printf '%s\n' "$sorted" | sed -n "$(((RUNS + 1) / 2))p")
    drvset=$(printf '%s\n' "${drvs[@]}" | sort -u | paste -sd/ -)
    drowset=$(printf '%s\n' "${drows[@]}" | sort -u | paste -sd/ -)
    tlist=$(printf '%s\n' "${times[@]}" | paste -sd';' -)
    echo "B,$sort_name,$region,notag,p1,$RUNS,$drvset,$drowset,$tlist,$mn,$md,$mx" >> "$CSV"
    echo "[notag7] $sort_name/$region  drv=$drvset rows=$drowset  min=$mn med=$md max=$mx"
  done
done

echo "[notag7] 저장: $CSV"
