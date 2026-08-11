#!/usr/bin/env bash
# 3회 채취에서 편차가 컸던 A 칸만 골라 7회로 다시 잰다 (캠페인 README: 경계 칸 7회 허용).
#
# SQL 생성부·커서 도출부는 bench/explain-a-grid.sh와 글자 그대로 같다 — 문장이 갈리면
# 재측정이 다른 것을 재게 된다. 바뀐 것은 반복 횟수와 대상 칸 목록뿐이다.
#
# 대상 (3회 채취의 편차·플랜 전환 때문에 고른 칸)
#   latest/town4/hotcombo p1·p2 — p1은 태그 주도, p2는 지역 주도로 갈렸고 p2가 0.33~2.63ms로 흔들렸다
#   popular/town4/hotcombo p1·p2 — 위와 짝을 맞춰 본다
#   popular/town4/mainrare p1     — 3회 중 하나가 1.66ms로 튀었다
#   popular/city18/mainsubrare p1 — 0.557 대 1.18ms
#   latest/city18/zero p1         — 0.0174 대 0.0639ms
#
# 사용: bench/probe-repeat7-a.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/probe-repeat7-a.csv"
RUNS=7
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }

TOWN1="301"
TOWN4="301,302,303,304"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
declare -A REGION_TOWNS=([town1]="$TOWN1" [town4]="$TOWN4" [city18]="$CITY18")
declare -A TAG_CONDS=(
  [notag]=""
  [maincafe]="= 1"
  [hotcombo]="= 1;IN (7,8,9,10);IN (11,12,13,14,15,16)"
  [mainrare]="= 5"
  [mainsubrare]="= 2;= 21"
  [synth20]="= 40"
  [zero]="= 41"
)

# 정렬|지역|태그셀|페이지
CELLS=(
  "latest|town4|hotcombo|p1"
  "latest|town4|hotcombo|p2"
  "popular|town4|hotcombo|p1"
  "popular|town4|hotcombo|p2"
  "popular|town4|mainrare|p1"
  "popular|city18|mainsubrare|p1"
  "latest|city18|zero|p1"
)

ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

pop() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2$3
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
WHERE p.town_id IN ($1)
  AND p.active = 1
$2$3
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

pop_cursor() { echo "
  AND (ps.popular_score < ${1}E0
       OR (ps.popular_score = ${1}E0 AND ps.place_id > $2))"; }
lat_cursor() { echo "
  AND (p.created_at < '$1'
       OR (p.created_at = '$1' AND p.id < $2))"; }

conds_sql_of() {
  local idcol="$1" raw="$2" out="" c conds
  [ -z "$raw" ] && { echo ""; return; }
  IFS=';' read -r -a conds <<<"$raw"
  for c in "${conds[@]}"; do out="$out
$(ex "$idcol" "$c")"; done
  echo "$out"
}

first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }

: > "$CSV"
echo "form,sort,region,tagcell,page,runs,driving_tables,driving_rows,times_ms,min_ms,median_ms,max_ms" >> "$CSV"

for cell in "${CELLS[@]}"; do
  IFS='|' read -r sort_name region cid page <<<"$cell"
  towns="${REGION_TOWNS[$region]}"
  raw="${TAG_CONDS[$cid]}"
  idcol="ps.place_id"; [ "$sort_name" = "latest" ] && idcol="p.id"
  conds_sql="$(conds_sql_of "$idcol" "$raw")"

  cur=""
  if [ "$page" = "p2" ]; then
    if [ "$sort_name" = "popular" ]; then base="$(pop "$towns" "$conds_sql" "")"
    else base="$(lat "$towns" "$conds_sql" "")"; fi
    pair="$(q "$base" | sed -n 11p | awk -F'\t' '{print $2"|"$1}')"
    [ -z "$pair" ] && { echo "[probe7] $sort_name/$region/$cid/p2 건너뜀 — 11번째 행 없음"; continue; }
    key="${pair%%|*}"; pid="${pair##*|}"
    if [ "$sort_name" = "popular" ]; then cur="$(pop_cursor "$key" "$pid")"
    else cur="$(lat_cursor "$key" "$pid")"; fi
  fi

  if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "$cur")"
  else sql="$(lat "$towns" "$conds_sql" "$cur")"; fi

  suffix=""; [ "$page" = "p2" ] && suffix="-p2"
  f="$OUTDIR/a-${region}-${cid}-${sort_name}${suffix}-r7.txt"
  {
    echo "################################################################"
    echo "# A 재채취 7회 · $sort_name · $region · $cid · $page"
    echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
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
  echo "A,$sort_name,$region,$cid,$page,$RUNS,$drvset,$drowset,$tlist,$mn,$md,$mx" >> "$CSV"
  echo "[probe7] $sort_name/$region/$cid/$page  drv=$drvset rows=$drowset  min=$mn med=$md max=$mx"
done

echo "[probe7] 저장: $CSV"
