#!/usr/bin/env bash
# A 형상(마이그레이션 전 스키마 + 옵티마이저 자연 플랜) 그리드 채취.
#
# 부하 테스트가 아니다. EXPLAIN ANALYZE 실행계획 채취다.
# SQL은 현행 PlaceListDbQueryRepository가 만드는 문장 그대로다 —
#   인기순: place_stats 단독, score_calculated_at IS NOT NULL, EXISTS 태그 필터
#   최신순: places 기준 + LEFT JOIN place_stats, p.active = 1, EXISTS 태그 필터
# 힌트는 없다. 조건부 힌트를 철회한 상태(regionFirstHint=false)의 SQL과 바이트 동일해야 하므로
# joinOrderHint()·qbNameHint()가 빈 문자열을 돌려주는 형태를 그대로 쓴다. LIMIT 11.
#
# 채취 매트릭스 (캠페인 README §4 그리드)
#   지역 3 (동네 1곳 100 / 동네 4곳 400 / 시 단위 18곳 1,800)
#   × 태그 7 (무태그 · 흔한 메인 · 흔한 메인+서브 2그룹[핫패스] · 비인기 메인 ·
#             메인+희귀 서브 · 합성 규모 20 · 0건)
#   × 정렬 2                                                            = 42칸
#   + 커서 2페이지 (핫패스 칸만, 지역 3 × 정렬 2)                        = 6칸
#   칸마다 워밍업 1회 + EXPLAIN ANALYZE 3회, 중앙값
#
# 커서 2페이지는 같은 칸의 1페이지를 먼저 돌려 **11번째 행**의 sortKey·place_id를 읽고
# 그 값으로 술어를 만든다. 매치가 11건에 못 미치는 칸은 커서가 존재하지 않으므로 건너뛰고
# 그 사유를 CSV에 남긴다 (동네 1곳 핫패스가 여기 해당 — 매치 10건).
#
# ⚠️ 형상을 바꾸지 않는다. DDL도 ANALYZE도 하지 않고 읽기만 한다.
#    합성 태그(id 40·41) 시딩은 bench/seed-synthetic-tags.sh가 채취 전에 1회 끝내 둔다.
#
# B 채취는 이 스크립트를 복사해 pop()/lat()의 문장과 태그 술어(ex → 비트마스크)만 바꾼다 —
# 지역 세트·태그 세트·커서 도출 방식·출력 규약은 그대로 두어야 칸이 서로 대응한다.
#
# 사용: bench/explain-a-grid.sh [셀id필터]
#   인자 없음 — 전체 48칸을 돌고 summary-a.csv를 새로 만든다
#   인자 있음 — 셀 id(<지역>-<태그>)에 그 문자열이 든 칸만 돌고 CSV에 이어 쓴다
#                예: bench/explain-a-grid.sh city18-hotcombo
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/summary-a.csv"
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

# ---------- 지역 ----------
# 동네 하나가 정확히 100곳이라 곳수 × 100이 후보 수다 (직전 두 캠페인과 같은 대역).
TOWN1="301"
TOWN4="301,302,303,304"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
REGIONS=("town1:$TOWN1" "town4:$TOWN4" "city18:$CITY18")

# ---------- 태그 ----------
# 태그id|셀id|EXISTS 조건들(; 구분, 빈 값이면 무태그)
# 실태그 규모(벤치 DB 실측): 카페 1,146 / 음식 1,094 / 이색공간 985(MAIN 최소) / 바술집 185
# 옵션군은 실제 요청 형태 그대로 IN 리스트다 — 카페의 OPTION1 4개, OPTION2 6개.
TAGCELLS=(
  "notag|"
  "maincafe|= 1"
  "hotcombo|= 1;IN (7,8,9,10);IN (11,12,13,14,15,16)"
  "mainrare|= 5"
  "mainsubrare|= 2;= 21"
  "synth20|= 40"
  "zero|= 41"
)
CURSOR_CELL="hotcombo"   # 커서 2페이지를 채취하는 칸

# 태그 EXISTS 한 조각 (현행 appendTagFilters). $1=장소 id 컬럼 $2=태그 조건
ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

# $1=towns $2=태그 조건들 $3=커서 술어(빈 값이면 1페이지)
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

# 커서 술어. 앱의 문장과 같다 (findPopularRows / findLatestRows).
# 인기 점수는 E0을 붙여 double 리터럴로 만든다 — 앱이 커서 sortKey를 double로 바인딩하고
# MySQL이 DECIMAL 컬럼을 DOUBLE로 올려 비교하므로, 경계 판정을 같은 타입으로 맞춘다.
pop_cursor() { echo "
  AND (ps.popular_score < ${1}E0
       OR (ps.popular_score = ${1}E0 AND ps.place_id > $2))"; }
lat_cursor() { echo "
  AND (p.created_at < '$1'
       OR (p.created_at = '$1' AND p.id < $2))"; }

FILTER="${1:-}"

# 트리에서 처음 읽는 테이블 = 드라이빙. 트리는 깊이우선이고 네스티드 루프의 바깥쪽이 먼저 찍히므로
# 노드 줄("-> ") 중 " on X"가 처음 나오는 줄의 X가 드라이빙 테이블이다.
first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
# 드라이빙 노드가 실제로 읽은 행 수 — 조기 종료 판정의 근거다
# (지역 주도인데 11 언저리면 LIMIT에서 끊긴 것, 지역 후보 수만큼이면 전수 스캔).
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
top_rows()     { printf '%s\n' "$1" | head -1 | grep -o 'actual time=[^)]*' \
                   | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }
# 플랜 형상 태그: 세미조인·구체화·정렬 노드의 존재 여부
shape_of() {
  local s=""
  printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && s="${s}sort+"
  printf '%s\n' "$1" | grep -qiE 'FirstMatch'          && s="${s}firstmatch+"
  printf '%s\n' "$1" | grep -qiE 'Materialize'         && s="${s}materialize+"
  printf '%s\n' "$1" | grep -qiE 'Nested loop'         && s="${s}nl+"
  echo "${s%+}"
}

# 3회가 서로 다르면 그대로 드러낸다 — 조용히 run1만 쓰지 않는다.
agree3() { if [ "$1" = "$2" ] && [ "$2" = "$3" ]; then echo "$1"; else echo "*$1/$2/$3"; fi; }
median3() { printf '%s\n' "$1" "$2" "$3" | sort -g | sed -n 2p; }

if [ -z "$FILTER" ]; then
  : > "$CSV"
  echo "form,sort,region,tagcell,page,driving_table,driving_rows,top_rows,shape,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"
fi

# ---------- 환경 기록 ----------
HDR="$OUTDIR/_environment-a.txt"
[ -n "$FILTER" ] && HDR="$OUTDIR/_environment-a_${FILTER}.txt"
{
  echo "================================================================"
  echo "# A 형상 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
  echo "# 지역: town1=($TOWN1) town4=($TOWN4) city18=($CITY18)"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회 중앙값"
  echo "# 이 스크립트는 DDL도 ANALYZE도 하지 않는다 — 마이그레이션 전 스키마 그대로다"
  echo "================================================================"
  qt "SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.columns
       WHERE table_schema='$DB' AND table_name='place_stats' ORDER BY ordinal_position;"
  qt "SELECT index_name, seq_in_index, column_name FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name IN ('place_stats','place_tag','places')
       ORDER BY table_name, index_name, seq_in_index;"
  qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
             (SELECT COUNT(*) FROM place_stats) AS ps_rows,
             (SELECT COUNT(*) FROM place_stats WHERE score_calculated_at IS NOT NULL) AS scored,
             (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
             (SELECT COUNT(*) FROM tags) AS tags_rows;"
  qt "SELECT t.id, t.name, t.type, COUNT(pt.id) AS linked
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id=t.id
       WHERE t.id IN (1,2,5,7,8,9,10,11,12,13,14,15,16,21,40,41)
       GROUP BY t.id, t.name, t.type ORDER BY t.id;"
} > "$HDR"
echo "[a-grid] 환경 기록: $HDR"

# ---------- 한 칸 채취 ----------
# $1=정렬 $2=지역명 $3=towns $4=태그셀id $5=태그조건들 $6=페이지(p1|p2) $7=커서술어
run_cell() {
  local sort_name="$1" region="$2" towns="$3" cid="$4" conds_raw="$5" page="$6" cur="$7"
  local idcol="ps.place_id"; [ "$sort_name" = "latest" ] && idcol="p.id"
  local conds_sql="" c
  if [ -n "$conds_raw" ]; then
    local conds; IFS=';' read -r -a conds <<<"$conds_raw"
    for c in "${conds[@]}"; do conds_sql="$conds_sql
$(ex "$idcol" "$c")"; done
  fi
  local sql
  if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "$cur")"
  else sql="$(lat "$towns" "$conds_sql" "$cur")"; fi

  local suffix=""; [ "$page" = "p2" ] && suffix="-p2"
  local f="$OUTDIR/a-${region}-${cid}-${sort_name}${suffix}.txt"
  {
    echo "################################################################"
    echo "# A · $sort_name · $region · $cid · $page"
    echo "# towns: $towns"
    echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "################################################################"
    echo "--- SQL ---"
    echo "$sql"
  } > "$f"

  q "$sql" >/dev/null || true            # 워밍업
  local times=() drvs=() drows=() trows=() shapes=() run tree t d dr tr
  for run in 1 2 3; do
    tree="$(q "EXPLAIN ANALYZE $sql")"
    { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
    t="$(printf '%s\n' "$tree" | head -1 \
          | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
    d="$(driving_of "$tree" || true)"
    dr="$(driving_rows "$tree" || true)"
    tr="$(top_rows "$tree" || true)"
    times+=("${t:-NA}"); drvs+=("${d:-none}"); drows+=("${dr:-NA}"); trows+=("${tr:-NA}")
    shapes+=("$(shape_of "$tree")")
  done
  local drvtab drv_rows top_r shp med
  drvtab="$(agree3 "${drvs[0]}" "${drvs[1]}" "${drvs[2]}")"
  drv_rows="$(agree3 "${drows[0]}" "${drows[1]}" "${drows[2]}")"
  top_r="$(agree3 "${trows[0]}" "${trows[1]}" "${trows[2]}")"
  shp="$(agree3 "${shapes[0]}" "${shapes[1]}" "${shapes[2]}")"
  med="$(median3 "${times[0]}" "${times[1]}" "${times[2]}")"
  echo "A,$sort_name,$region,$cid,$page,$drvtab,$drv_rows,$top_r,$shp,${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
  echo "[a-grid] $sort_name/$region/$cid/$page  drv=$drvtab drvrows=$drv_rows top=$top_r shape=$shp med=${med}ms"
}

# ---------- 커서 도출 ----------
# 1페이지를 그대로 돌려 11번째 행의 (sortKey, place_id)를 읽는다. 11건이 안 되면 빈 값.
cursor_of() {
  local sort_name="$1" towns="$2" conds_raw="$3"
  local idcol="ps.place_id"; [ "$sort_name" = "latest" ] && idcol="p.id"
  local conds_sql="" c
  local conds; IFS=';' read -r -a conds <<<"$conds_raw"
  for c in "${conds[@]}"; do conds_sql="$conds_sql
$(ex "$idcol" "$c")"; done
  local sql
  if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "")"
  else sql="$(lat "$towns" "$conds_sql" "")"; fi
  # -N --raw 탭 구분: 1열=place_id, 2열=sortKey(점수 또는 created_at)
  q "$sql" | sed -n 11p | awk -F'\t' '{print $2"|"$1}'
}

# ---------- 채취 ----------
for region_pair in "${REGIONS[@]}"; do
  region="${region_pair%%:*}"; towns="${region_pair#*:}"
  for tagcell in "${TAGCELLS[@]}"; do
    cid="${tagcell%%|*}"; conds_raw="${tagcell#*|}"
    if [ -n "$FILTER" ] && [[ "${region}-${cid}" != *"$FILTER"* ]]; then continue; fi
    for sort_name in popular latest; do
      run_cell "$sort_name" "$region" "$towns" "$cid" "$conds_raw" "p1" ""
      # 커서 2페이지는 핫패스 칸만
      if [ "$cid" = "$CURSOR_CELL" ]; then
        cur_pair="$(cursor_of "$sort_name" "$towns" "$conds_raw")"
        if [ -z "$cur_pair" ]; then
          echo "A,$sort_name,$region,$cid,p2,SKIPPED_LT11ROWS,NA,NA,NA,NA,NA,NA,NA" >> "$CSV"
          echo "[a-grid] $sort_name/$region/$cid/p2  건너뜀 — 1페이지 매치가 11건 미만이라 커서가 없다"
          continue
        fi
        key="${cur_pair%%|*}"; pid="${cur_pair##*|}"
        if [ "$sort_name" = "popular" ]; then cur="$(pop_cursor "$key" "$pid")"
        else cur="$(lat_cursor "$key" "$pid")"; fi
        echo "[a-grid] 커서($sort_name/$region): key=$key place_id=$pid"
        run_cell "$sort_name" "$region" "$towns" "$cid" "$conds_raw" "p2" "$cur"
      fi
    done
  done
done

echo "[a-grid] 저장: $CSV / 트리 $OUTDIR/"
