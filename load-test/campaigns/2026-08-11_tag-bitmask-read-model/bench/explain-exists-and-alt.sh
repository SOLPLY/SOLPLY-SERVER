#!/usr/bin/env bash
# 조인 기반 구현 대안 · AND-all 술어 채취 (EXISTS-AND).
#
# 왜 있나: 태그 필터 스펙은 "요청 태그 전부 포함"(AND-all)이고, 현재 구현은
# `(ps.tag_bitmask & M) = M` 단일 술어다(확인 채취: results/summary-b-and.csv).
# 같은 스키마·같은 데이터 위에서 **구현 방식만 바꾼** 대안을 나란히 재서
# 공정 비교 원자료를 만든다. 대안은 태그 하나당 EXISTS 하나:
#   AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id=ps.place_id AND pt.tag_id=<한개>)
# IN 리스트도 아니고 tags 조인도 없다 — 태그 수만큼 EXISTS가 늘어난다.
#
# 통제: 접속·SQL 골격·워밍업 1 + 3회 중앙값·칸 구성은 explain-b-and-confirm.sh를
# 그대로 물려받는다. 인기순은 place_stats + score_calculated_at IS NOT NULL,
# 최신순도 place_stats — FROM이 현재 구현과 같다. 바뀐 것은 태그 술어 한 곳뿐이다.
#
# ⚠️ SELECT / EXPLAIN만 한다. DDL·DML·ANALYZE 없음. V34 적용 상태 그대로 쓴다.
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/summary-exists-and.csv"
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

# ---------- 지역 ---------- (b-and 확인 채취와 동일)
TOWN1="301"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"

# ---------- 태그 셀 ----------
# 셀id|태그 id 목록(공백 구분)|대응 비트마스크(b-and 대조용)|설명
#   카페 1 / 커피·디저트 7 / 작업 8 / 콘센트많음 14
#   OPTION1 전부 {7,8,9,10}, OPTION2 전부 {11,12,13,14,15,16}
CELLS=(
  "city18|andcafe|1|2|카페 단독 — EXISTS 1개"
  "city18|andcafe7|1 7|130|카페+커피/디저트 — EXISTS 2개"
  "city18|andcafe8-14|1 8 14|16642|카페+작업+콘센트많음 — EXISTS 3개"
  "city18|andhotall|1 7 8 9 10 11 12 13 14 15 16|130946|카페+OPTION1 전부+OPTION2 전부 — EXISTS 11개, 매치 0 극단"
  "town1|andcafe7|1 7|130|동네 1곳 — EXISTS 2개"
  "town1|andcafe8-14|1 8 14|16642|동네 1곳 — EXISTS 3개"
  "town1|andcafe|1|2|동네 1곳 카페 단독 — 매치 ≥11인 조기 종료 관측용"
)

# 태그별 EXISTS를 AND로 이어 붙인다. 태그 하나 = EXISTS 하나 (IN 리스트 아님).
exists_and() {
  local t
  for t in $1; do
    echo "  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = ps.place_id AND pt.tag_id = $t)"
  done
}

# SQL 골격은 explain-b-and-confirm.sh의 pop()/lat()와 바이트 동일하다.
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

# ---------- 판정 함수 ----------
# 주도 노드 = 트리에서 처음 등장하는 " on " 접근 노드(가장 왼쪽 깊은 곳).
first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
top_rows()     { printf '%s\n' "$1" | head -1 | grep -o 'actual time=[^)]*' \
                   | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }

# 추정/실제 대조는 **Limit을 뺀 최상위 비용 노드**에서 읽는다.
# Limit 노드의 rows=는 LIMIT 값으로 잘린 수(항상 11)라 카디널리티 추정이 아니다 —
# 여기를 그냥 첫 (cost= 줄로 잡으면 조기 종료 계획에서 전부 11이 찍힌다.
# est = 그 노드의 옵티마이저 추정 행 수. 실제 통과 행 수와의 대조는 match_rows 열과 한다
# (조기 종료 계획에서는 pre_rows도 LIMIT에 잘려 전량이 아니다).
est_line()  { printf '%s\n' "$1" | grep '(cost=' | grep -vE '^[[:space:]]*-> Limit' | head -1; }
est_rows()  { est_line "$1" | sed 's/(actual.*//' | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
pre_rows()  { est_line "$1" | grep -o 'actual time=[^)]*' | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }

# 조인 노드 수 = "Nested loop"/"Hash join" 노드 개수. EXISTS가 조인으로 풀렸는지 센다.
join_nodes() { printf '%s\n' "$1" | grep -cE '^[[:space:]]*-> (Nested loop|Hash (semi)?join|Inner hash join)' || true; }

# 세미조인 전략 흔적. MySQL이 EXISTS를 어떻게 풀었는지: FirstMatch / Materialize /
# weedout(Remove duplicates) / 유니크키로 중복이 애초에 없어 평범한 inner join이 된 경우.
semijoin_of() {
  local s=""
  printf '%s\n' "$1" | grep -qi 'FirstMatch'                    && s="${s}firstmatch+"
  printf '%s\n' "$1" | grep -qi 'Materialize'                   && s="${s}materialize+"
  printf '%s\n' "$1" | grep -qiE 'Remove duplicate|weedout'      && s="${s}weedout+"
  printf '%s\n' "$1" | grep -qi 'Loose ?[Ss]can'                 && s="${s}loosescan+"
  echo "${s:-none}" | sed 's/+$//'
}
shape_of() {
  local s=""
  printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && s="${s}sort+"
  printf '%s\n' "$1" | grep -qi 'FirstMatch'           && s="${s}firstmatch+"
  printf '%s\n' "$1" | grep -qi 'Materialize'          && s="${s}materialize+"
  printf '%s\n' "$1" | grep -qi 'Nested loop'          && s="${s}nl+"
  echo "${s%+}"
}
agree3() { if [ "$1" = "$2" ] && [ "$2" = "$3" ]; then echo "$1"; else echo "*$1/$2/$3"; fi; }
median3() { printf '%s\n' "$1" "$2" "$3" | sort -g | sed -n 2p; }

: > "$CSV"
echo "form,sort,region,tagcell,mask,tag_ids,tag_count,driving_table,driving_rows,estimated_rows,prelimit_rows,top_rows,match_rows,join_nodes,semijoin,shape,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"

# ---------- 환경 기록 ----------
HDR="$OUTDIR/_environment-exists-and.txt"
{
  echo "================================================================"
  echo "# EXISTS-AND 대안 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
  echo "# 술어: 태그 하나당 EXISTS 하나, AND로 연결 (IN 리스트 아님, tags 조인 없음)"
  echo "# 대조군: results/summary-b-and.csv — 같은 칸, 술어만 (tag_bitmask & M) = M"
  echo "# 지역: town1=($TOWN1) city18=($CITY18)"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회 중앙값"
  echo "================================================================"
  qt "SELECT version, description, installed_by, installed_on FROM flyway_schema_history
       ORDER BY installed_rank DESC LIMIT 3;"
  qt "SELECT index_name, seq_in_index, column_name FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name='place_tag'
       ORDER BY index_name, seq_in_index;"
  qt "SELECT index_name, seq_in_index, column_name FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name='place_stats'
       ORDER BY index_name, seq_in_index;"
  qt "SELECT t.id, t.name, t.type, COUNT(pt.id) AS linked
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id=t.id
       WHERE t.id IN (1,7,8,9,10,11,12,13,14,15,16) GROUP BY t.id, t.name, t.type ORDER BY t.id;"
  qt "SELECT COUNT(*) AS place_tag_rows FROM place_tag;"
  qt "SELECT COUNT(*) AS place_stats_rows FROM place_stats;"
  qt "SELECT @@optimizer_switch AS optimizer_switch;"
} > "$HDR"
echo "[exists-and] 환경 기록: $HDR"

# ---------- 한 칸 채취 ----------
run_cell() {
  local sort_name="$1" region="$2" towns="$3" cid="$4" tags="$5" mask="$6" note="$7"
  local cond; cond="$(exists_and "$tags")"
  local ntags; ntags="$(printf '%s\n' $tags | wc -l | tr -d ' ')"
  local sql match
  if [ "$sort_name" = "popular" ]; then
    sql="$(pop "$towns" "$cond")"; match="$(cnt_pop "$towns" "$cond")"
  else
    sql="$(lat "$towns" "$cond")"; match="$(cnt_lat "$towns" "$cond")"
  fi

  local f="$OUTDIR/exists-and-${region}-${cid}-${sort_name}.txt"
  {
    echo "################################################################"
    echo "# EXISTS-AND · $sort_name · $region · $cid · tags=[$tags] (n=$ntags)"
    echo "# $note"
    echo "# 대응 비트마스크(b-and): mask=$mask"
    echo "# towns: $towns"
    echo "# 매치 행 수(LIMIT 없음): $match"
    echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "################################################################"
    echo "--- SQL ---"
    echo "$sql"
  } > "$f"

  q "$sql" >/dev/null || true            # 워밍업
  local times=() drvs=() drows=() erows=() prows=() trows=() jn=() sj=() shapes=()
  local run tree t
  for run in 1 2 3; do
    tree="$(q "EXPLAIN ANALYZE $sql")"
    { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
    t="$(printf '%s\n' "$tree" | head -1 \
          | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
    times+=("${t:-NA}")
    drvs+=("$(driving_of "$tree" || echo none)")
    drows+=("$(driving_rows "$tree" || echo NA)")
    erows+=("$(est_rows "$tree" || echo NA)")
    prows+=("$(pre_rows "$tree" || echo NA)")
    trows+=("$(top_rows "$tree" || echo NA)")
    jn+=("$(join_nodes "$tree")")
    sj+=("$(semijoin_of "$tree")")
    shapes+=("$(shape_of "$tree")")
  done
  local drvtab drv_rows est_r pre_r top_r jnn sjn shp med
  drvtab="$(agree3 "${drvs[0]}" "${drvs[1]}" "${drvs[2]}")"
  drv_rows="$(agree3 "${drows[0]}" "${drows[1]}" "${drows[2]}")"
  est_r="$(agree3 "${erows[0]}" "${erows[1]}" "${erows[2]}")"
  pre_r="$(agree3 "${prows[0]}" "${prows[1]}" "${prows[2]}")"
  top_r="$(agree3 "${trows[0]}" "${trows[1]}" "${trows[2]}")"
  jnn="$(agree3 "${jn[0]}" "${jn[1]}" "${jn[2]}")"
  sjn="$(agree3 "${sj[0]}" "${sj[1]}" "${sj[2]}")"
  shp="$(agree3 "${shapes[0]}" "${shapes[1]}" "${shapes[2]}")"
  med="$(median3 "${times[0]}" "${times[1]}" "${times[2]}")"
  echo "EXISTS-AND,$sort_name,$region,$cid,$mask,\"$tags\",$ntags,$drvtab,$drv_rows,$est_r,$pre_r,$top_r,$match,$jnn,$sjn,$shp,${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
  echo "[exists-and] $sort_name/$region/$cid n=$ntags drv=$drvtab drvrows=$drv_rows est=$est_r pre=$pre_r top=$top_r match=$match joins=$jnn sj=$sjn med=${med}ms"
}

for cell in "${CELLS[@]}"; do
  IFS='|' read -r region cid tags mask note <<<"$cell"
  towns="$TOWN1"; [ "$region" = "city18" ] && towns="$CITY18"
  for sort_name in popular latest; do
    run_cell "$sort_name" "$region" "$towns" "$cid" "$tags" "$mask" "$note"
  done
done

echo "[exists-and] 저장: $CSV / 트리 $OUTDIR/"
