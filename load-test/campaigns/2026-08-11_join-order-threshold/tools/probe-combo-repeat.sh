#!/usr/bin/env bash
# 프로브 A — 시 단위 조합 4칸을 7회씩 다시 잰다. 읽기 전용.
#
# 왜: 1차 채취(3회)에서 강제 arm의 산포가 컸다 (인기 3.57/2.42/2.76, 최신 2.35/2.07/3.45).
# 3회 중앙값으로는 자연 대 강제의 대소를 말하기 어려워 회차만 늘린다.
# 형상·통계·SQL 모두 1차와 같다 — 바꾼 것은 반복 횟수뿐이다.
#
# 기존 3회 파일은 건드리지 않는다. 결과는 _r7 접미사로 따로 남긴다.
#
# 사용: tools/probe-combo-repeat.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/probe_repeat7.csv"
RUNS=7
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }

CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
CID="combo_cafe_opt1_opt2"

ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

pop() { echo "SELECT $2 ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($CITY)
  AND ps.score_calculated_at IS NOT NULL
$1
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT $2 p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
WHERE p.town_id IN ($CITY)
  AND p.active = 1
$1
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

driving_of() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' \
                 | sed -n 's/.* on \([^ ]*\).*/\1/p' | head -1; }
has_sort()  { printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && echo y || echo n; }
has_mat()   { printf '%s\n' "$1" | grep -q 'Materialize with deduplication' && echo y || echo n; }
uniq_or_mix() { printf '%s\n' "$@" | sort -u | paste -sd/ - ; }

: > "$CSV"
echo "sort,region,size,mode,driving_table,sort_node,materialize,runs_ms,median_ms" >> "$CSV"

for sort_name in popular latest; do
  drv="ps"; [ "$sort_name" = "latest" ] && drv="p"
  idcol="ps.place_id"; [ "$sort_name" = "latest" ] && idcol="p.id"
  conds="$(ex "$idcol" "= 1")
$(ex "$idcol" "IN (7,8,9,10)")
$(ex "$idcol" "IN (11,12,13,14,15,16)")"
  for mode in natural forced; do
    hint=""; [ "$mode" = "forced" ] && hint="/*+ JOIN_PREFIX($drv) */"
    if [ "$sort_name" = "popular" ]; then sql="$(pop "$conds" "$hint")"; else sql="$(lat "$conds" "$hint")"; fi

    f="$OUTDIR/${sort_name}_city_${CID}_${mode}_r7.txt"
    { echo "################################################################"
      echo "# 프로브 A · $sort_name · city · $CID · $mode · ${RUNS}회"
      echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
      echo "################################################################"
      echo "--- SQL ---"; echo "$sql"; } > "$f"

    q "$sql" >/dev/null || true                    # 워밍업
    times=(); drvs=(); srts=(); mats=()
    for run in $(seq 1 $RUNS); do
      tree="$(q "EXPLAIN ANALYZE $sql")"
      { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
      t="$(printf '%s\n' "$tree" | head -1 \
            | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
      times+=("${t:-NA}"); drvs+=("$(driving_of "$tree")")
      srts+=("$(has_sort "$tree")"); mats+=("$(has_mat "$tree")")
    done
    med="$(printf '%s\n' "${times[@]}" | sort -g | sed -n "$(( (RUNS+1)/2 ))p")"
    joined="$(printf '%s;' "${times[@]}")"; joined="${joined%;}"
    echo "$sort_name,city,$CID,$mode,$(uniq_or_mix "${drvs[@]}"),$(uniq_or_mix "${srts[@]}"),$(uniq_or_mix "${mats[@]}"),$joined,$med" >> "$CSV"
    echo "[probeA] $sort_name/$mode  drv=$(uniq_or_mix "${drvs[@]}") mat=$(uniq_or_mix "${mats[@]}") med=${med}ms  ($joined)"
  done
done

echo "[probeA] 저장: $CSV"
