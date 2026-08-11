#!/usr/bin/env bash
# B 형상(V34 적용 + 비트마스크 술어) 그리드 채취.
#
# bench/explain-a-grid.sh의 복사본이다. **지역 세트·태그 세트·커서 도출 방식·파일명 규약·
# 판정 함수는 글자 그대로 같다** — 칸이 서로 대응해야 A/B 비교가 성립하므로 여기를 손대면
# 비교가 무너진다. 바뀐 것은 SQL 두 개(pop/lat)와 태그 술어(EXISTS → 비트마스크)뿐이다.
#
# SQL은 V34 이후 PlaceListDbQueryRepository가 만드는 문장 그대로다 —
#   인기순: place_stats 단독, score_calculated_at IS NOT NULL, 마스크 술어
#   최신순: place_stats 단독(조인 없음), active·score_calculated_at 술어 **없음**, 마스크 술어
# 힌트는 없다 — 조건부 힌트 일습은 이 설계에서 삭제된다. LIMIT 11.
#
# 채취 매트릭스 (캠페인 README §4 그리드, A와 동일)
#   지역 3 (동네 1곳 100 / 동네 4곳 400 / 시 단위 18곳 1,800)
#   × 태그 7 (무태그 · 흔한 메인 · 흔한 메인+서브 2그룹[핫패스] · 비인기 메인 ·
#             메인+희귀 서브 · 합성 규모 20 · 0건)
#   × 정렬 2                                                            = 42칸
#   + 커서 2페이지 (핫패스 칸만, 지역 3 × 정렬 2)                        = 6칸
#   칸마다 워밍업 1회 + EXPLAIN ANALYZE 3회, 중앙값
#
# 커서 2페이지는 **B의 1페이지 결과에서 새로 도출한다** — A의 커서 값을 물려받지 않는다.
# 두 값이 갈리면 그것은 결과 집합 차이의 신호이므로 보고 대상이다(S1의 보조 관측).
# 매치가 11건에 못 미치는 칸은 커서가 존재하지 않아 건너뛰고 사유를 CSV에 남긴다
# (동네 1곳 핫패스가 여기 해당 — A와 같은 이유로 매치 10건).
#
# ⚠️ 이 스크립트는 DDL도 ANALYZE도 하지 않는다. V34 적용과 ANALYZE는 채취 전에 끝나 있어야 한다.
# ⚠️ 합성 태그(id 40·41)는 A 채취 때 깐 것을 그대로 쓴다. 롤백은 B 채취가 끝난 뒤.
#
# 사용: bench/explain-b-grid.sh [셀id필터]
#   인자 없음 — 전체 48칸을 돌고 summary-b.csv를 새로 만든다
#   인자 있음 — 셀 id(<지역>-<태그>)에 그 문자열이 든 칸만 돌고 CSV에 이어 쓴다
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/summary-b.csv"
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

# ---------- 지역 ---------- (A와 동일)
TOWN1="301"
TOWN4="301,302,303,304"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
REGIONS=("town1:$TOWN1" "town4:$TOWN4" "city18:$CITY18")

# ---------- 태그 ----------
# 셀id|그룹 마스크들(; 구분, 빈 값이면 무태그)
# 비트 자리 = tag id다 (TagBitmask). 그룹 하나가 마스크 하나이고, 그룹 안의 OR만 마스크가
# 흡수한다 — 그룹 사이 AND는 술어 여러 개가 만든다. 합치면 OR가 되어 의미가 뒤집힌다.
#   카페     id 1  → 1<<1  = 2
#   음식     id 2  → 1<<2  = 4
#   이색공간 id 5  → 1<<5  = 32
#   바/술집  id 21 → 1<<21 = 2097152
#   OPTION1(카페) {7,8,9,10}          → 128+256+512+1024                     = 1920
#   OPTION2(카페) {11,12,13,14,15,16} → 2048+4096+8192+16384+32768+65536     = 129024
#   합성 규모20 id 40 → 1<<40 = 1099511627776
#   0건       id 41 → 1<<41 = 2199023255552
TAGCELLS=(
  "notag|"
  "maincafe|2"
  "hotcombo|2;1920;129024"
  "mainrare|32"
  "mainsubrare|4;2097152"
  "synth20|1099511627776"
  "zero|2199023255552"
)
CURSOR_CELL="hotcombo"   # 커서 2페이지를 채취하는 칸

# 마스크 술어 한 조각 (V34 이후 appendTagFilters). $1=마스크
# `!= 0`을 `= mask`로 바꾸면 그룹 안 OR가 AND로 뒤집힌다.
bm() { echo "  AND (ps.tag_bitmask & $1) != 0"; }

# $1=towns $2=마스크 술어들 $3=커서 술어(빈 값이면 1페이지)
pop() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2$3
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
# 최신순도 place_stats 단독이다 — places 기준도, LEFT JOIN도, p.active도, COALESCE도 없다.
# score_calculated_at 술어도 걸지 않는다 (미채점 신규 장소가 최신순 맨 앞에 와야 한다).
lat() { echo "SELECT ps.place_id, ps.created_at, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
$2$3
ORDER BY ps.created_at DESC, ps.place_id DESC LIMIT 11"; }

# 커서 술어. 앱의 문장과 같다 (findPopularRows / findLatestRows).
# 인기 점수는 E0을 붙여 double 리터럴로 만든다 — 앱이 커서 sortKey를 double로 바인딩하고
# MySQL이 DECIMAL 컬럼을 DOUBLE로 올려 비교하므로, 경계 판정을 같은 타입으로 맞춘다.
pop_cursor() { echo "
  AND (ps.popular_score < ${1}E0
       OR (ps.popular_score = ${1}E0 AND ps.place_id > $2))"; }
lat_cursor() { echo "
  AND (ps.created_at < '$1'
       OR (ps.created_at = '$1' AND ps.place_id < $2))"; }

FILTER="${1:-}"

# 트리에서 처음 읽는 테이블 = 드라이빙. (A와 같은 판정 함수)
first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
top_rows()     { printf '%s\n' "$1" | head -1 | grep -o 'actual time=[^)]*' \
                   | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }
# 플랜 형상 태그: 세미조인·구체화·정렬 노드의 존재 여부 — 주장 ①의 직접 근거다
shape_of() {
  local s=""
  printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && s="${s}sort+"
  printf '%s\n' "$1" | grep -qiE 'FirstMatch'          && s="${s}firstmatch+"
  printf '%s\n' "$1" | grep -qiE 'Materialize'         && s="${s}materialize+"
  printf '%s\n' "$1" | grep -qiE 'Nested loop'         && s="${s}nl+"
  echo "${s%+}"
}

agree3() { if [ "$1" = "$2" ] && [ "$2" = "$3" ]; then echo "$1"; else echo "*$1/$2/$3"; fi; }
median3() { printf '%s\n' "$1" "$2" "$3" | sort -g | sed -n 2p; }

if [ -z "$FILTER" ]; then
  : > "$CSV"
  echo "form,sort,region,tagcell,page,driving_table,driving_rows,top_rows,shape,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"
fi

# ---------- 환경 기록 ----------
HDR="$OUTDIR/_environment-b.txt"
[ -n "$FILTER" ] && HDR="$OUTDIR/_environment-b_${FILTER}.txt"
{
  echo "================================================================"
  echo "# B 형상 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
  echo "# 지역: town1=($TOWN1) town4=($TOWN4) city18=($CITY18)"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회 중앙값"
  echo "# 스키마: V34 수동 적용 후 (installed_by='bench-manual'), ANALYZE TABLE place_stats 완료"
  echo "# 이 스크립트는 DDL도 ANALYZE도 하지 않는다"
  echo "================================================================"
  qt "SELECT version, description, installed_by, installed_on FROM flyway_schema_history
       ORDER BY installed_rank DESC LIMIT 4;"
  qt "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.columns
       WHERE table_schema='$DB' AND table_name='place_stats' ORDER BY ordinal_position;"
  qt "SELECT index_name, seq_in_index, column_name, collation FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name IN ('place_stats','place_tag','places')
       ORDER BY table_name, index_name, seq_in_index;"
  qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
             (SELECT COUNT(*) FROM place_stats) AS ps_rows,
             (SELECT COUNT(*) FROM place_stats WHERE score_calculated_at IS NOT NULL) AS scored,
             (SELECT COUNT(*) FROM place_stats WHERE created_at IS NULL) AS created_at_null,
             (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
             (SELECT COUNT(*) FROM tags) AS tags_rows;"
  qt "SELECT t.id, t.name, t.type, COUNT(pt.id) AS linked
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id=t.id
       WHERE t.id IN (1,2,5,7,8,9,10,11,12,13,14,15,16,21,40,41)
       GROUP BY t.id, t.name, t.type ORDER BY t.id;"
  # 마스크 백필 대조 — 저장값과 place_tag 재계산값이 어긋난 행은 0이어야 한다
  qt "SELECT COUNT(*) AS mask_mismatch FROM place_stats ps
        LEFT JOIN (SELECT place_id, BIT_OR(1<<tag_id) AS m FROM place_tag GROUP BY place_id) t
               ON t.place_id = ps.place_id
       WHERE ps.tag_bitmask <> COALESCE(t.m, 0);"
} > "$HDR"
echo "[b-grid] 환경 기록: $HDR"

# ---------- 한 칸 채취 ----------
# $1=정렬 $2=지역명 $3=towns $4=태그셀id $5=마스크들 $6=페이지(p1|p2) $7=커서술어
run_cell() {
  local sort_name="$1" region="$2" towns="$3" cid="$4" masks_raw="$5" page="$6" cur="$7"
  local conds_sql="" m
  if [ -n "$masks_raw" ]; then
    local masks; IFS=';' read -r -a masks <<<"$masks_raw"
    for m in "${masks[@]}"; do conds_sql="$conds_sql
$(bm "$m")"; done
  fi
  local sql
  if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "$cur")"
  else sql="$(lat "$towns" "$conds_sql" "$cur")"; fi

  local suffix=""; [ "$page" = "p2" ] && suffix="-p2"
  local f="$OUTDIR/b-${region}-${cid}-${sort_name}${suffix}.txt"
  {
    echo "################################################################"
    echo "# B · $sort_name · $region · $cid · $page"
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
  echo "B,$sort_name,$region,$cid,$page,$drvtab,$drv_rows,$top_r,$shp,${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
  echo "[b-grid] $sort_name/$region/$cid/$page  drv=$drvtab drvrows=$drv_rows top=$top_r shape=$shp med=${med}ms"
}

# ---------- 커서 도출 ----------
# B의 1페이지를 그대로 돌려 11번째 행의 (sortKey, place_id)를 읽는다. 11건이 안 되면 빈 값.
cursor_of() {
  local sort_name="$1" towns="$2" masks_raw="$3"
  local conds_sql="" m
  local masks; IFS=';' read -r -a masks <<<"$masks_raw"
  for m in "${masks[@]}"; do conds_sql="$conds_sql
$(bm "$m")"; done
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
    cid="${tagcell%%|*}"; masks_raw="${tagcell#*|}"
    if [ -n "$FILTER" ] && [[ "${region}-${cid}" != *"$FILTER"* ]]; then continue; fi
    for sort_name in popular latest; do
      run_cell "$sort_name" "$region" "$towns" "$cid" "$masks_raw" "p1" ""
      # 커서 2페이지는 핫패스 칸만
      if [ "$cid" = "$CURSOR_CELL" ]; then
        cur_pair="$(cursor_of "$sort_name" "$towns" "$masks_raw")"
        if [ -z "$cur_pair" ]; then
          echo "B,$sort_name,$region,$cid,p2,SKIPPED_LT11ROWS,NA,NA,NA,NA,NA,NA,NA" >> "$CSV"
          echo "[b-grid] $sort_name/$region/$cid/p2  건너뜀 — 1페이지 매치가 11건 미만이라 커서가 없다"
          continue
        fi
        key="${cur_pair%%|*}"; pid="${cur_pair##*|}"
        if [ "$sort_name" = "popular" ]; then cur="$(pop_cursor "$key" "$pid")"
        else cur="$(lat_cursor "$key" "$pid")"; fi
        echo "[b-grid] 커서($sort_name/$region): key=$key place_id=$pid"
        run_cell "$sort_name" "$region" "$towns" "$cid" "$masks_raw" "p2" "$cur"
      fi
    done
  done
done

echo "[b-grid] 저장: $CSV / 트리 $OUTDIR/"
