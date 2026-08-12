#!/usr/bin/env bash
# 프로브 C — 강제 arm의 붕괴가 인덱스 형태 탓인지 통계 탓인지 가른다. ⚠️ DDL을 건다.
#
# 1차 채취(V33 형상, 갓 ANALYZE한 통계)의 시 단위 강제 arm은 2.4~2.8ms였는데,
# 2026-08-10 recheck(V32 형상, 그때의 통계)에서는 0.81~0.98ms였다. 두 채취 사이에
# 인덱스 형태와 통계가 함께 바뀌어 있어 원인을 가를 수 없다.
# 여기서는 통계를 지금 것으로 둔 채 인덱스만 V32 형태로 되돌려 같은 4칸을 잰다.
#
#   v32idx = idx_place_tag_tag_id (tag_id)          ← 되돌린 형태
#   v33idx = idx_place_tag_tag_place (tag_id, place_id)  ← 원래대로 (원복 목표)
#
# ⚠️ 끝나거나 중단되면 반드시 V33 형상으로 되돌린다(trap). 원복 후 ANALYZE와 검증까지 한다.
#    fk_place_tag_tag가 tag_id 선두 인덱스를 요구하므로 두 방향 모두 "만들고 나서 지운다".
#
# 사용: tools/probe-index-attribution.sh
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV="results/probe_index_attribution.csv"
mkdir -p "$OUTDIR"

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }

has_index() { [ "$(q "SELECT COUNT(*) FROM information_schema.statistics
                       WHERE table_schema='$DB' AND table_name='place_tag'
                         AND index_name='$1'" | tr -d '[:space:]')" != "0" ]; }

shape_v32() {
  has_index idx_place_tag_tag_id    || q "CREATE INDEX idx_place_tag_tag_id ON place_tag (tag_id)"
  has_index idx_place_tag_tag_place && q "DROP INDEX idx_place_tag_tag_place ON place_tag"
  q "ANALYZE TABLE place_tag" >/dev/null
}
shape_v33() {
  has_index idx_place_tag_tag_place || q "CREATE INDEX idx_place_tag_tag_place ON place_tag (tag_id, place_id)"
  has_index idx_place_tag_tag_id    && q "DROP INDEX idx_place_tag_tag_id ON place_tag"
  q "ANALYZE TABLE place_tag" >/dev/null
}
restore() { echo "[probeC] 원복: V33 형상으로 되돌린다" >&2; shape_v33 || true; }
trap restore EXIT

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
has_sort() { printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && echo y || echo n; }
has_mat()  { printf '%s\n' "$1" | grep -q 'Materialize with deduplication' && echo y || echo n; }
uniq_or_mix() { printf '%s\n' "$@" | sort -u | paste -sd/ - ; }

: > "$CSV"
echo "sort,region,size,mode,index_shape,driving_table,sort_node,materialize,run1_ms,run2_ms,run3_ms,median_ms" >> "$CSV"

echo "[probeC] 인덱스를 V32 형태(tag_id 단일)로 전환 + ANALYZE"
shape_v32
qt "SELECT index_name, seq_in_index, column_name, cardinality FROM information_schema.statistics
     WHERE table_schema='$DB' AND table_name='place_tag' ORDER BY index_name, seq_in_index;"

for sort_name in popular latest; do
  drv="ps"; idcol="ps.place_id"
  [ "$sort_name" = "latest" ] && { drv="p"; idcol="p.id"; }
  conds="$(ex "$idcol" "= 1")
$(ex "$idcol" "IN (7,8,9,10)")
$(ex "$idcol" "IN (11,12,13,14,15,16)")"
  for mode in natural forced; do
    hint=""; [ "$mode" = "forced" ] && hint="/*+ JOIN_PREFIX($drv) */"
    if [ "$sort_name" = "popular" ]; then sql="$(pop "$conds" "$hint")"; else sql="$(lat "$conds" "$hint")"; fi

    f="$OUTDIR/${sort_name}_city_${CID}_${mode}_v32idx.txt"
    { echo "################################################################"
      echo "# 프로브 C · $sort_name · city · $CID · $mode · 인덱스 V32 형태(tag_id 단일)"
      echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
      echo "################################################################"
      echo "--- SQL ---"; echo "$sql"; } > "$f"

    q "$sql" >/dev/null || true
    times=(); drvs=(); srts=(); mats=()
    for run in 1 2 3; do
      tree="$(q "EXPLAIN ANALYZE $sql")"
      { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
      t="$(printf '%s\n' "$tree" | head -1 \
            | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
      times+=("${t:-NA}"); drvs+=("$(driving_of "$tree")")
      srts+=("$(has_sort "$tree")"); mats+=("$(has_mat "$tree")")
    done
    med="$(printf '%s\n' "${times[@]}" | sort -g | sed -n 2p)"
    echo "$sort_name,city,$CID,$mode,v32idx,$(uniq_or_mix "${drvs[@]}"),$(uniq_or_mix "${srts[@]}"),$(uniq_or_mix "${mats[@]}"),${times[0]},${times[1]},${times[2]},$med" >> "$CSV"
    echo "[probeC] $sort_name/$mode  drv=$(uniq_or_mix "${drvs[@]}") mat=$(uniq_or_mix "${mats[@]}") med=${med}ms"
  done
done

echo "[probeC] 채취 끝 — 원복은 trap이 한다"
trap - EXIT
restore

echo "[probeC] 원복 검증"
qt "SELECT index_name, seq_in_index, column_name, cardinality FROM information_schema.statistics
     WHERE table_schema='$DB' AND table_name='place_tag' ORDER BY index_name, seq_in_index;"
qt "SELECT (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
           (SELECT COUNT(*) FROM tags) AS tags_rows,
           (SELECT COUNT(*) FROM place_tag WHERE tag_id >= 9001) AS synthetic_rows;"
echo "[probeC] 저장: $CSV"
