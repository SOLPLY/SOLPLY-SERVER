#!/usr/bin/env bash
# B 형상 · AND-all 술어 확인 채취.
#
# 왜 있나: B 그리드(bench/explain-b-grid.sh)는 "그룹별 마스크 술어 3개"(그룹 안 OR,
# 그룹 사이 AND)로 쟀다. 태그 필터 스펙이 **"요청 태그 전부 포함"(AND-all)**으로 확정되어
# 술어가 `(ps.tag_bitmask & :m) = :m` **하나**로 바뀐다. 이 확인 채취는 두 가지만 본다.
#   ① 계획 형태가 그대로인가 (조인 0 · 세미조인 0 · 주도 노드 동일)
#   ② 기존 B 측정의 시 단위 대역 안에 머무는가
# 전면 재측정이 아니다 — 그리드 42칸을 다시 돌지 않는다.
#
# 접속·측정 규약은 explain-b-grid.sh를 글자 그대로 물려받는다(같은 컨테이너·같은 SQL 골격·
# 워밍업 1 + 3회 중앙값·같은 판정 함수). 바뀐 것은 술어 한 줄과 태그 셀 목록뿐이다.
#
# ⚠️ 합성 태그(id 40·41)는 B 채취 후 롤백되어 없다. 이 확인에는 필요 없다 — 다시 만들지 않는다.
# ⚠️ DDL도 ANALYZE도 하지 않는다. V34는 이미 벤치 DB에 적용되어 있다.
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/summary-b-and.csv"
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

# ---------- 지역 ---------- (B 그리드와 동일)
TOWN1="301"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"

# ---------- 태그 셀 ----------
# 셀id|AND-all 마스크|설명
# 비트 자리 = tag id. AND-all이므로 요청 태그 **전부**의 비트를 OR로 합친 값 하나다.
#   카페 1        → 1<<1  = 2
#   커피/디저트 7 → 1<<7  = 128
#   작업 8        → 1<<8  = 256
#   콘센트많음 14 → 1<<14 = 16384
#   OPTION1 전부 {7,8,9,10}          = 1920
#   OPTION2 전부 {11,12,13,14,15,16} = 129024
CELLS=(
  "city18|andcafe|2|카페 단독 — 단일 태그는 의미론 변화 없음, 기준선 일치 확인"
  "city18|andcafe7|130|카페+커피/디저트 — 그룹당 하나의 현실적 조합"
  "city18|andcafe8-14|16642|카페+작업+콘센트많음 — 다중 그룹 AND"
  "city18|andhotall|130946|카페+OPTION1 전부+OPTION2 전부 — 핫패스와 같은 태그 집합의 AND 해석(최악)"
  "town1|andcafe7|130|동네 1곳 — 조기 종료 확인"
  "town1|andcafe8-14|16642|동네 1곳 — 조기 종료 확인"
  # 보충 칸: 위 두 동네 칸은 AND-all이라 매치가 11건에 못 미쳐(9건·1건) LIMIT 조기 종료가
  # 애초에 발동할 수 없다 — 전수 스캔이 나오는 게 정상이라 "조기 종료 유지"의 증거가 못 된다.
  # 매치가 11건을 넘는 단일 태그 칸을 하나 더 두어 조기 종료 자체를 관측한다.
  "town1|andcafe|2|동네 1곳 카페 단독 — 매치 ≥11인 조기 종료 관측용 보충 칸"
)

# AND-all 술어. `!= 0`(그룹 OR)이 아니라 `= mask`(전부 포함)다 — 여기를 되돌리면 의미가 뒤집힌다.
bm_and() { echo "  AND (ps.tag_bitmask & $1) = $1"; }

# SQL 골격은 explain-b-grid.sh의 pop()/lat()와 바이트 동일하다.
pop() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT ps.place_id, ps.created_at, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
$2
ORDER BY ps.created_at DESC, ps.place_id DESC LIMIT 11"; }

# 매치 수 — LIMIT 없는 전체 통과 행 수. 정렬별 술어 차이(score_calculated_at)를 반영한다.
cnt_pop() { q "SELECT COUNT(*) FROM place_stats ps WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2"; }
cnt_lat() { q "SELECT COUNT(*) FROM place_stats ps WHERE ps.town_id IN ($1)
$2"; }

# ---------- 판정 함수 ---------- (explain-b-grid.sh와 동일)
first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
top_rows()     { printf '%s\n' "$1" | head -1 | grep -o 'actual time=[^)]*' \
                   | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }
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

: > "$CSV"
echo "form,sort,region,tagcell,mask,driving_table,driving_rows,top_rows,match_rows,shape,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"

# ---------- 환경 기록 ----------
HDR="$OUTDIR/_environment-b-and.txt"
{
  echo "================================================================"
  echo "# B · AND-all 확인 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
  echo "# 술어: (ps.tag_bitmask & M) = M  (요청 태그 전부 포함)"
  echo "# 지역: town1=($TOWN1) city18=($CITY18)"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회 중앙값"
  echo "# 합성 태그 40·41 롤백 상태 — 이 채취는 실태그만 쓴다"
  echo "================================================================"
  qt "SELECT version, description, installed_by, installed_on FROM flyway_schema_history
       ORDER BY installed_rank DESC LIMIT 3;"
  qt "SELECT index_name, seq_in_index, column_name FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name='place_stats'
       ORDER BY index_name, seq_in_index;"
  qt "SELECT t.id, t.name, t.type, COUNT(pt.id) AS linked
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id=t.id
       WHERE t.id IN (1,7,8,9,10,11,12,13,14,15,16) GROUP BY t.id, t.name, t.type ORDER BY t.id;"
  qt "SELECT COUNT(*) AS residual_synth_tags FROM tags WHERE id IN (40,41);"
  qt "SELECT COUNT(*) AS mask_mismatch FROM place_stats ps
        LEFT JOIN (SELECT place_id, BIT_OR(1<<tag_id) AS m FROM place_tag GROUP BY place_id) t
               ON t.place_id = ps.place_id
       WHERE ps.tag_bitmask <> COALESCE(t.m, 0);"
} > "$HDR"
echo "[b-and] 환경 기록: $HDR"

# ---------- 한 칸 채취 ----------
run_cell() {
  local sort_name="$1" region="$2" towns="$3" cid="$4" mask="$5" note="$6"
  local cond; cond="$(bm_and "$mask")"
  local sql match
  if [ "$sort_name" = "popular" ]; then
    sql="$(pop "$towns" "$cond")"; match="$(cnt_pop "$towns" "$cond")"
  else
    sql="$(lat "$towns" "$cond")"; match="$(cnt_lat "$towns" "$cond")"
  fi

  local f="$OUTDIR/b-and-${region}-${cid}-${sort_name}.txt"
  {
    echo "################################################################"
    echo "# B(AND-all) · $sort_name · $region · $cid · mask=$mask"
    echo "# $note"
    echo "# towns: $towns"
    echo "# 매치 행 수(LIMIT 없음): $match"
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
  echo "B-AND,$sort_name,$region,$cid,$mask,$drvtab,$drv_rows,$top_r,$match,$shp,${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
  echo "[b-and] $sort_name/$region/$cid  drv=$drvtab drvrows=$drv_rows top=$top_r match=$match shape=$shp med=${med}ms"
}

for cell in "${CELLS[@]}"; do
  IFS='|' read -r region cid mask note <<<"$cell"
  towns="$TOWN1"; [ "$region" = "city18" ] && towns="$CITY18"
  for sort_name in popular latest; do
    run_cell "$sort_name" "$region" "$towns" "$cid" "$mask" "$note"
  done
done

echo "[b-and] 저장: $CSV / 트리 $OUTDIR/"
