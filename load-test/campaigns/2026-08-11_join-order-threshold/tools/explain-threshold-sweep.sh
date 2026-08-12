#!/usr/bin/env bash
# 태그 규모를 스윕하며 조인 순서(드라이빙 테이블)가 태그→지역으로 뒤집히는 지점을 채취한다.
#
# 부하 테스트가 아니다. EXPLAIN ANALYZE 실행계획 채취다.
# SQL은 2026-08-10 두 캠페인 스크립트에서 그대로 가져왔다 (현행 PlaceListDbQueryRepository의
# 인기순·최신순, tags 조인 없는 V32 형상). 태그 id 자리만 파라미터화했고 문장은 고치지 않았다.
#
# 채취 매트릭스
#   (a) 합성 메인 태그 7규모 × 지역 2 × 정렬 2 × 형태 2(자연/강제) × 3회   = 56칸
#   (b) 메인+서브 조합 4가지 × 지역 2 × 정렬 2 × 자연만 × 3회               = 16칸
#   (c) 실태그 앵커 2개(카페 1,146 · 바/술집 185) × 지역 2 × 정렬 2 × 형태 2 = 16칸
#   (d) 실태그 3조각 조합(카페+옵션1군+옵션2군) × 지역 2 × 정렬 2 × 형태 2  = 8칸
#   합계 96칸 · EXPLAIN ANALYZE 288회 (+ 칸마다 워밍업 1회)
#   (d)는 합성 태그가 필요 없다 — 시딩 없이 실태그만으로 돈다.
#
# 강제 형태의 힌트는 2026-08-10 힌트 재검증 스크립트와 같다 —
# 인기순은 기준 테이블이 place_stats라 JOIN_PREFIX(ps), 최신순은 places라 JOIN_PREFIX(p).
#
# ⚠️ 이 스크립트는 형상을 바꾸지 않는다. 인덱스도 통계도 건드리지 않고 읽기만 한다.
#    합성 태그 시딩은 tools/seed-synthetic-tags.sh가 채취 전에 1회 끝내 둔다.
#
# 사용: tools/explain-threshold-sweep.sh [셀id필터]
#   인자 없음 — 전체 96칸을 돌고 summary.csv를 새로 만든다 (합성 태그 시딩이 먼저 필요하다)
#   인자 있음 — 셀 id에 그 문자열이 든 칸만 돌고 summary.csv에 이어 쓴다
#                예: tools/explain-threshold-sweep.sh combo_cafe_opt1_opt2   (실태그만, 시딩 불필요)
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/summary.csv"
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

TOWN="301"
CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"

# 태그 EXISTS 한 조각 (현행 appendTagFilters). $1=장소 id 컬럼 $2=태그 조건
ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

# $1=towns $2=조건 $3=힌트(빈 값이면 없음)
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

# ---------- 채취 셀 정의 ----------
# 그룹|셀id|태그조건들(; 구분)|형태들(공백 구분)
CELLS=(
  # (a) 합성 메인 태그 규모 스윕 — 자연 선택 + 지역 주도 강제
  "a|size0020|= 9001|natural forced"
  "a|size0050|= 9002|natural forced"
  "a|size0100|= 9003|natural forced"
  "a|size0185|= 9004|natural forced"
  "a|size0400|= 9005|natural forced"
  "a|size0700|= 9006|natural forced"
  "a|size1146|= 9007|natural forced"
  # (c) 실태그 앵커 — (a)의 같은 규모 칸과 나란히 본다 (합성 균등 분포 대 실분포)
  "c|real0185|= 21|natural forced"
  "c|real1146|= 1|natural forced"
  # (b) 복수 태그 — 드라이빙이 메인 고정인지 최소 카디널리티 태그인지 트리로 본다. 자연 선택만.
  "b|cafe1146_sub0020|= 1;= 9101|natural"
  "b|cafe1146_sub0100|= 1;= 9102|natural"
  "b|cafe1146_sub0400|= 1;= 9103|natural"
  "b|real_food1094_bar0185|= 2;= 21|natural"
  # (d) 실태그 3조각 조합 — #385의 출발 수치(서울+카페+옵션 2종, V32에서 자연 2.45 대 강제 0.81ms)를
  #     낸 2026-08-10_join-order-hint-recheck의 "흔한 태그 3개" 문장 그대로다. 그 격차가 V33
  #     (커버링) 형상에서도 남아 있는지 보려고 같은 조합을 자연/강제 두 형태로 다시 채취한다.
  #     동네 1곳도 같이 잰다 — 조합에서 동네 쪽 회귀가 없는지 확인용.
  "d|combo_cafe_opt1_opt2|= 1;IN (7,8,9,10);IN (11,12,13,14,15,16)|natural forced"
)

# 셀 id 필터. 인자를 주면 그 문자열을 포함하는 셀만 돌고 CSV에 이어 쓴다(기존 행 보존).
# 인자가 없으면 전체를 돌고 CSV를 새로 만든다.
FILTER="${1:-}"

# 트리에서 처음 읽는 테이블 = 드라이빙. 트리 출력은 깊이우선이고 네스티드 루프의 바깥쪽이 먼저 찍히므로
# 노드 줄("-> ") 중 " on X"가 처음 나오는 줄의 X가 드라이빙 테이블이다.
# SQL 본문이 아니라 트리 텍스트만 보도록 노드 줄로 한정한다.
driving_of() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' \
                 | sed -n 's/.* on \([^ ]*\).*/\1/p' | head -1; }
has_sort()   { printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && echo y || echo n; }

# 3회가 서로 다르면 그대로 드러낸다 — 조용히 run1만 쓰지 않는다.
agree3() {
  if [ "$1" = "$2" ] && [ "$2" = "$3" ]; then echo "$1"; else echo "*$1/$2/$3"; fi
}

median3() { printf '%s\n' "$1" "$2" "$3" | sort -g | sed -n 2p; }

if [ -z "$FILTER" ]; then
  : > "$CSV"
  echo "sort,region,size,mode,driving_table,sort_node,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"
fi

# 채취 헤더 (형상 고정 확인용)
# 필터 채취는 앞선 전체 채취의 환경 기록을 덮어쓰지 않는다 — 형상이 같은지 나란히 대볼 수 있게 남긴다.
HDR="$OUTDIR/_environment.txt"
if [ -n "$FILTER" ]; then HDR="$OUTDIR/_environment_${FILTER}.txt"; fi
{
  echo "================================================================"
  echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
  echo "# 동네 = town_id IN ($TOWN) / 시 = town_id IN ($CITY)"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회"
  echo "# 형상은 채취 내내 고정 — 이 스크립트는 DDL도 ANALYZE도 하지 않는다"
  echo "================================================================"
  qt "SELECT index_name, seq_in_index, column_name, cardinality
        FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name='place_tag'
       ORDER BY index_name, seq_in_index;"
  qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
             (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
             (SELECT COUNT(*) FROM place_tag WHERE tag_id>=9001) AS synthetic_pt_rows,
             (SELECT COUNT(*) FROM tags) AS tags_rows;"
  qt "SELECT tag_id, COUNT(*) AS linked FROM place_tag
       WHERE tag_id IN (1,2,21,9001,9002,9003,9004,9005,9006,9007,9101,9102,9103)
       GROUP BY tag_id ORDER BY tag_id;"
} > "$HDR"
echo "[sweep] 환경 기록: $HDR"

for cell in "${CELLS[@]}"; do
  IFS='|' read -r grp cid conds_raw modes <<<"$cell"
  if [ -n "$FILTER" ] && [[ "$cid" != *"$FILTER"* ]]; then continue; fi
  IFS=';' read -r -a conds <<<"$conds_raw"
  for region_pair in "town:$TOWN" "city:$CITY"; do
    region="${region_pair%%:*}"; towns="${region_pair#*:}"
    for sort_name in popular latest; do
      idcol="ps.place_id"; drv="ps"
      [ "$sort_name" = "latest" ] && { idcol="p.id"; drv="p"; }
      conds_sql=""
      for c in "${conds[@]}"; do conds_sql="$conds_sql
$(ex "$idcol" "$c")"; done
      for mode in $modes; do
        hint=""; [ "$mode" = "forced" ] && hint="/*+ JOIN_PREFIX($drv) */"
        if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "$hint")"
        else sql="$(lat "$towns" "$conds_sql" "$hint")"; fi

        f="$OUTDIR/${sort_name}_${region}_${cid}_${mode}.txt"
        {
          echo "################################################################"
          echo "# 그룹 $grp · $sort_name · $region · $cid · $mode"
          echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
          echo "################################################################"
          echo "--- SQL ---"
          echo "$sql"
        } > "$f"

        q "$sql" >/dev/null || true          # 워밍업
        times=(); drvs=(); srts=()
        for run in 1 2 3; do
          tree="$(q "EXPLAIN ANALYZE $sql")"
          {
            echo
            echo "--- EXPLAIN ANALYZE (run $run) ---"
            echo "$tree"
          } >> "$f"
          t="$(printf '%s\n' "$tree" | head -1 \
                | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
          times+=("${t:-NA}")
          d="$(driving_of "$tree")"; drvs+=("${d:-none}")
          srts+=("$(has_sort "$tree")")
        done

        drvtab="$(agree3 "${drvs[0]}" "${drvs[1]}" "${drvs[2]}")"
        sortnode="$(agree3 "${srts[0]}" "${srts[1]}" "${srts[2]}")"
        med="$(median3 "${times[0]}" "${times[1]}" "${times[2]}")"
        echo "$sort_name,$region,$cid,$mode,$drvtab,$sortnode,${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
        echo "[sweep] $sort_name/$region/$cid/$mode  drv=$drvtab sort=$sortnode med=${med}ms"
      done
    done
  done
done

echo "[sweep] 저장: $CSV / 트리 $OUTDIR/"
