#!/usr/bin/env bash
# 프로브 B — 시 단위 강제 arm에서 세미조인 전략을 힌트로 바꿔본다. 읽기 전용.
#
# 1차 채취에서 JOIN_PREFIX 강제 arm의 두 IN-list EXISTS가 `Materialize with deduplication`으로
# 바뀌어 있었다 (2026-08-10 V32 형상 채취에서는 상관 EXISTS의 Limit 1 프로브였다).
# 구체화를 힌트로 막으면 V32형 트리와 시간으로 돌아오는지 본다.
#
#   base : JOIN_PREFIX 만                                        (1차와 같은 arm, 대조용)
#   B1   : + NO_SEMIJOIN(@qb1) NO_SEMIJOIN(@qb2)                 세미조인 변환 자체를 막는다
#   B2   : + SEMIJOIN(@qb1 FIRSTMATCH) SEMIJOIN(@qb2 FIRSTMATCH) 세미조인은 두되 구체화만 막는다
#
# QB_NAME은 두 IN-list EXISTS 블록에만 붙인다 (tag_id = 1 블록은 1차와 같게 둔다).
# 힌트가 무시되면 그 사실이 남도록 EXPLAIN 직후 SHOW WARNINGS를 같은 세션에서 찍어 파일에 넣는다.
#
# 사용: tools/probe-semijoin-hints.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/probe_semijoin.csv"
mkdir -p "$OUTDIR"

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
CID="combo_cafe_opt1_opt2"

# tag_id = 1 블록은 이름 없이, 두 IN-list 블록에만 QB_NAME을 붙인다.
conds_qb() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id = 1)
  AND EXISTS (SELECT /*+ QB_NAME(qb1) */ 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id IN (7,8,9,10))
  AND EXISTS (SELECT /*+ QB_NAME(qb2) */ 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id IN (11,12,13,14,15,16))"; }

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
has_sort() { printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && echo y || echo n; }
has_mat()  { printf '%s\n' "$1" | grep -q 'Materialize with deduplication' && echo y || echo n; }
has_dep()  { printf '%s\n' "$1" | grep -q 'subquery in condition; dependent' && echo y || echo n; }
uniq_or_mix() { printf '%s\n' "$@" | sort -u | paste -sd/ - ; }

ARMS=(
  "base|"
  "nosemijoin|NO_SEMIJOIN(@qb1) NO_SEMIJOIN(@qb2)"
  "sjfirstmatch|SEMIJOIN(@qb1 FIRSTMATCH) SEMIJOIN(@qb2 FIRSTMATCH)"
)

: > "$CSV"
echo "sort,region,size,mode,driving_table,sort_node,materialize,dependent_subq,run1_ms,run2_ms,run3_ms,median_ms,hint_warning_count" >> "$CSV"

for sort_name in popular latest; do
  drv="ps"; idcol="ps.place_id"
  [ "$sort_name" = "latest" ] && { drv="p"; idcol="p.id"; }
  conds="$(conds_qb "$idcol")"
  for arm in "${ARMS[@]}"; do
    IFS='|' read -r aname extra <<<"$arm"
    hint="/*+ JOIN_PREFIX($drv)${extra:+ $extra} */"
    if [ "$sort_name" = "popular" ]; then sql="$(pop "$conds" "$hint")"; else sql="$(lat "$conds" "$hint")"; fi

    f="$OUTDIR/${sort_name}_city_${CID}_forced_${aname}.txt"
    { echo "################################################################"
      echo "# 프로브 B · $sort_name · city · $CID · forced+$aname"
      echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
      echo "################################################################"
      echo "--- SQL ---"; echo "$sql"
      echo
      echo "--- EXPLAIN FORMAT=TREE + SHOW WARNINGS (힌트 수용 여부) ---"; } > "$f"
    # 같은 세션에서 EXPLAIN 직후 SHOW WARNINGS — 힌트가 무시되면 여기 Warning으로 남는다.
    qt "EXPLAIN FORMAT=TREE $sql; SHOW WARNINGS;" >> "$f"
    warncnt="$(q "EXPLAIN FORMAT=TREE $sql; SHOW WARNINGS;" | grep -c $'^Warning\t' || true)"

    q "$sql" >/dev/null || true                    # 워밍업
    times=(); drvs=(); srts=(); mats=(); deps=()
    for run in 1 2 3; do
      tree="$(q "EXPLAIN ANALYZE $sql")"
      { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
      t="$(printf '%s\n' "$tree" | head -1 \
            | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
      times+=("${t:-NA}"); drvs+=("$(driving_of "$tree")")
      srts+=("$(has_sort "$tree")"); mats+=("$(has_mat "$tree")"); deps+=("$(has_dep "$tree")")
    done
    med="$(printf '%s\n' "${times[@]}" | sort -g | sed -n 2p)"
    echo "$sort_name,city,$CID,forced_$aname,$(uniq_or_mix "${drvs[@]}"),$(uniq_or_mix "${srts[@]}"),$(uniq_or_mix "${mats[@]}"),$(uniq_or_mix "${deps[@]}"),${times[0]},${times[1]},${times[2]},$med,$warncnt" >> "$CSV"
    echo "[probeB] $sort_name/forced_$aname  drv=$(uniq_or_mix "${drvs[@]}") mat=$(uniq_or_mix "${mats[@]}") dep=$(uniq_or_mix "${deps[@]}") warn=$warncnt med=${med}ms"
  done
done

echo "[probeB] 저장: $CSV"
