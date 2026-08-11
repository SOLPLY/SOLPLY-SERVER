#!/usr/bin/env bash
# 지역 후보 수 R을 축으로 스윕하며 조인 순서(드라이빙 테이블)의 전환 구간을 채취한다.
#
# 부하 테스트가 아니다. EXPLAIN ANALYZE 실행계획 채취다.
# SQL 문장(pop/lat 생성 함수, EXISTS 조각, 힌트 형태)은 2026-08-11_join-order-threshold의
# explain-threshold-sweep.sh에서 글자 그대로 가져왔다 (현행 PlaceListDbQueryRepository의
# 인기순·최신순, tags 조인 없는 미발동 형태). 바꾼 것은 축뿐이다.
#
# 직전 캠페인은 지역을 두 점(동네 100 / 시 1,800)으로 고정하고 태그 규모를 스윕했다.
# 그래서 나온 임계 1,000은 R=1,800 한 점의 상수다. 이번에는 R을 6점으로 벌리고
# 태그 규모를 R의 **비율**로 놓는다 — 전환점이 절대 규모에 붙어 있는지 비율에 붙어 있는지
# 가르려면 R을 움직여야 한다.
#
# 채취 매트릭스
#   R 6점 {300, 600, 1000, 1800, 3600, 6000}  (연속 동네 대역 301~ , 동네당 정확히 100곳)
#   × 비율 격자 {0.15 .22 .30 .40 .52 .64 .80} (+ R ≤ 1000 은 1.0)  = 45개 (R, 비율) 조합
#   × 정렬 2 (popular / latest) × 형태 2 (natural / forced)          = 180칸
#   칸마다 워밍업 1회 + EXPLAIN ANALYZE 3회 = 540회
#
# 태그 조건은 단일 `= <tag_id>` EXISTS 하나뿐이다 — 옵션군(IN 리스트)이 없으므로 직전
# 캠페인이 겪은 "순서를 고정하면 옵션군 EXISTS가 구체화로 바뀐다" 문제가 생기지 않는다.
# 따라서 힌트는 JOIN_PREFIX 단독이고 SEMIJOIN 힌트는 쓰지 않는다.
# 강제 형태의 힌트는 직전 캠페인과 같다 — 인기순은 기준 테이블이 place_stats라
# JOIN_PREFIX(ps), 최신순은 places라 JOIN_PREFIX(p).
#
# ⚠️ 이 스크립트는 형상을 바꾸지 않는다. 인덱스도 통계도 건드리지 않고 읽기만 한다.
#    합성 태그 시딩은 tools/seed-synthetic-tags.sh가 채취 전에 1회 끝내 둔다.
#
# 사용: tools/explain-region-sweep.sh [셀id필터]
#   인자 없음 — 전체 180칸을 돌고 summary.csv를 새로 만든다
#   인자 있음 — 셀 id(r<R>_ratio<비율>)에 그 문자열이 든 칸만 돌고 summary.csv에 이어 쓴다
#                예: tools/explain-region-sweep.sh r1800        (R=1800 줄만)
#                    tools/explain-region-sweep.sh r3600_ratio0.40
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

# ---------- 격자 정의 (시드 스크립트와 글자 그대로 같아야 한다) ----------
REGIONS=(300 600 1000 1800 3600 6000)
RATIOS_BASE=(0.15 0.22 0.30 0.40 0.52 0.64 0.80)
RATIO_EXTRA=1.00
SYNTH_MIN_ID=9001

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
uniq_sizes() { grid_rows | awk '{print $3}' | sort -n -u; }

# 크기 → 태그 id (시드가 크기 오름차순으로 9001부터 부여한다)
declare -A TAGID
_tid=$SYNTH_MIN_ID
while read -r s; do TAGID["$s"]=$_tid; _tid=$((_tid + 1)); done < <(uniq_sizes)

# 지역 대역: 동네 301부터 R/100개 연속 (동네 하나가 정확히 100곳)
towns_of() { seq 301 $((300 + $1 / 100)) | paste -sd, -; }

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

# 셀 id 필터. 인자를 주면 그 문자열을 포함하는 셀만 돌고 CSV에 이어 쓴다(기존 행 보존).
FILTER="${1:-}"

# 트리에서 처음 읽는 테이블 = 드라이빙. 트리 출력은 깊이우선이고 네스티드 루프의 바깥쪽이 먼저 찍히므로
# 노드 줄("-> ") 중 " on X"가 처음 나오는 줄의 X가 드라이빙 테이블이다.
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
  echo "sort,region_size,ratio,tag_size,mode,driving_table,sort_node,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"
fi

# 채취 헤더 (형상 고정 확인용)
# 필터 채취는 앞선 전체 채취의 환경 기록을 덮어쓰지 않는다 — 형상이 같은지 나란히 대볼 수 있게 남긴다.
HDR="$OUTDIR/_environment.txt"
if [ -n "$FILTER" ]; then HDR="$OUTDIR/_environment_${FILTER}.txt"; fi
{
  echo "================================================================"
  echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
  echo "# 지역 대역:"
  for r in "${REGIONS[@]}"; do echo "#   R=$r → town_id IN ($(towns_of "$r"))"; done
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회"
  echo "# 형상은 채취 내내 고정 — 이 스크립트는 DDL도 ANALYZE도 하지 않는다"
  echo "================================================================"
  qt "SELECT index_name, seq_in_index, column_name, cardinality
        FROM information_schema.statistics
       WHERE table_schema='$DB' AND table_name='place_tag'
       ORDER BY index_name, seq_in_index;"
  qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
             (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
             (SELECT COUNT(*) FROM place_tag WHERE tag_id>=$SYNTH_MIN_ID) AS synthetic_pt_rows,
             (SELECT COUNT(*) FROM tags) AS tags_rows;"
  qt "SELECT t.id AS tag_id, t.name, COUNT(pt.id) AS linked
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id = t.id
       WHERE t.id >= $SYNTH_MIN_ID
       GROUP BY t.id, t.name ORDER BY t.id;"
  qt "SELECT town_id, COUNT(*) AS active_places FROM places
       WHERE active=1 AND town_id BETWEEN 301 AND 360
       GROUP BY town_id ORDER BY town_id;"
} > "$HDR"
echo "[sweep] 환경 기록: $HDR"

# 격자를 먼저 배열로 받는다 — while read 루프 안에서 docker exec -i를 부르면 그 프로세스가
# 루프의 stdin(격자 스트림)을 통째로 삼켜 첫 칸만 돌고 끝난다.
GRID=()
while read -r line; do [ -n "$line" ] && GRID+=("$line"); done < <(grid_rows)

for gline in "${GRID[@]}"; do
  read -r R ratio tsize <<<"$gline"
  cid="r${R}_ratio${ratio}"
  if [ -n "$FILTER" ] && [[ "$cid" != *"$FILTER"* ]]; then continue; fi
  tag_id="${TAGID[$tsize]}"
  towns="$(towns_of "$R")"
  for sort_name in popular latest; do
    idcol="ps.place_id"; drv="ps"
    [ "$sort_name" = "latest" ] && { idcol="p.id"; drv="p"; }
    conds_sql="
$(ex "$idcol" "= $tag_id")"
    for mode in natural forced; do
      hint=""; [ "$mode" = "forced" ] && hint="/*+ JOIN_PREFIX($drv) */"
      if [ "$sort_name" = "popular" ]; then sql="$(pop "$towns" "$conds_sql" "$hint")"
      else sql="$(lat "$towns" "$conds_sql" "$hint")"; fi

      f="$OUTDIR/${sort_name}_${cid}_${mode}.txt"
      {
        echo "################################################################"
        echo "# $sort_name · R=$R · ratio=$ratio · tag_size=$tsize (tag_id=$tag_id) · $mode"
        echo "# towns: $towns"
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
      echo "$sort_name,$R,$ratio,$tsize,$mode,$drvtab,$sortnode,${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
      echo "[sweep] $sort_name/R=$R/ratio=$ratio/n=$tsize/$mode  drv=$drvtab sort=$sortnode med=${med}ms"
    done
  done
done

echo "[sweep] 저장: $CSV / 트리 $OUTDIR/"
