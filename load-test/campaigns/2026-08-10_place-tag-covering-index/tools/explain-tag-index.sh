#!/usr/bin/env bash
# place_tag의 태그 방향 인덱스 형상을 오가며 태그 필터 조회의 실행계획을 채취한다.
#
# before = idx_place_tag_tag_id (tag_id)          ← 현행. 리프에 place_id가 없다
# after  = idx_place_tag_tag_place (tag_id, place_id)  ← 커버링
#
# SQL은 현행 PlaceListDbQueryRepository(V32 형상)의 문장을 옮긴 것이다.
# 태그 조합 4가지 × 정렬 2가지 × 조회 범위 2가지 × 형상 2가지 = 32건.
# 각각 워밍업 1회 후 3회 측정하고, 문서에는 3회 중앙값을 싣는다.
#
# ⚠️ 이 스크립트는 벤치 DB에 DDL을 건다. 종료·중단 시 반드시 before 형상으로 되돌린다(trap).
#
# 사용: tools/explain-tag-index.sh [라벨]      (기본 라벨: v32)
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:-v32}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
OUT="results/explain/$LABEL.txt"
mkdir -p results/explain

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw solply_bench_db -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t solply_bench_db -e "$1" 2>/dev/null; }

# ---------- 인덱스 형상 전환 ----------
# 새 인덱스를 먼저 만들고 옛것을 지운다 — 그 사이에도 fk_place_tag_tag의 인덱스 요구가 깨지지 않는다.
has_index() { [ "$(q "SELECT COUNT(*) FROM information_schema.statistics
                       WHERE table_schema='solply_bench_db' AND table_name='place_tag'
                         AND index_name='$1'" | tr -d '[:space:]')" != "0" ]; }

shape_before() {
  has_index idx_place_tag_tag_id   || q "CREATE INDEX idx_place_tag_tag_id ON place_tag (tag_id)"
  has_index idx_place_tag_tag_place && q "DROP INDEX idx_place_tag_tag_place ON place_tag"
  q "ANALYZE TABLE place_tag" >/dev/null
}
shape_after() {
  has_index idx_place_tag_tag_place || q "CREATE INDEX idx_place_tag_tag_place ON place_tag (tag_id, place_id)"
  has_index idx_place_tag_tag_id    && q "DROP INDEX idx_place_tag_tag_id ON place_tag"
  q "ANALYZE TABLE place_tag" >/dev/null
}
restore() { echo "[explain] 인덱스를 before 형상으로 되돌린다" >&2; shape_before || true; }
trap restore EXIT

TOWN="301"
CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"

# 태그 EXISTS 한 조각 (현행 appendTagFilters). $1=장소 id 컬럼 $2=태그 조건
ex() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }

# $1=towns $2=조건 목록
pop() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
WHERE p.town_id IN ($1)
  AND p.active = 1
$2
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

# 2026-08-06 캠페인과 같은 4조합. 이름 | 태그 조건들(| 구분)
COMBOS=(
  "흔한 태그 1개|= 1"
  "흔한 태그 3개|= 1|IN (7,8,9,10)|IN (11,12,13,14,15,16)"
  "희귀 서브태그(185건)|= 2|IN (21)"
  "결과 0건 태그|= 31"
)

timings() {
  local sql="$1" out=""
  q "$sql" >/dev/null || true                       # 워밍업
  for _ in 1 2 3; do
    out="$out $(q "EXPLAIN ANALYZE $sql" | head -1 \
        | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
  done
  echo "$out"
}

: > "$OUT"
{
  echo "================================================================"
  echo "# 라벨: $LABEL / 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: solply_bench_db"
  echo "# 형상 before = idx_place_tag_tag_id (tag_id)"
  echo "# 형상 after  = idx_place_tag_tag_place (tag_id, place_id)"
  echo "# 두 형상 모두 ANALYZE TABLE place_tag 직후에 측정한다"
  echo "# 동네 1개 = $TOWN / 시 단위 = $CITY"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회"
  echo "================================================================"
  qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
             (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
             (SELECT COUNT(*) FROM tags) AS tags_rows,
             (SELECT SUM(active=0) FROM tags) AS inactive_tags,
             (SELECT COUNT(*) FROM place_stats WHERE score_calculated_at IS NOT NULL) AS scored;"
} >> "$OUT"

for shape in before after; do
  "shape_$shape"
  {
    echo
    echo "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@"
    echo "@ 인덱스 형상: $shape"
    echo "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@"
    qt "SELECT index_name, seq_in_index, column_name, cardinality
          FROM information_schema.statistics
         WHERE table_schema='solply_bench_db' AND table_name='place_tag'
         ORDER BY index_name, seq_in_index;"
  } >> "$OUT"

  for scope_pair in "동네 1개:$TOWN" "시 단위 18개:$CITY"; do
    scope_name="${scope_pair%%:*}"; towns="${scope_pair#*:}"
    for combo in "${COMBOS[@]}"; do
      IFS='|' read -r cname c1 c2 c3 <<<"$combo"
      for sort_name in 인기순 최신순; do
        idcol="ps.place_id"; [ "$sort_name" = "최신순" ] && idcol="p.id"
        conds="$(ex "$idcol" "$c1")"
        [ -n "${c2:-}" ] && conds="$conds
$(ex "$idcol" "$c2")"
        [ -n "${c3:-}" ] && conds="$conds
$(ex "$idcol" "$c3")"
        if [ "$sort_name" = "인기순" ]; then sql="$(pop "$towns" "$conds")"; else sql="$(lat "$towns" "$conds")"; fi
        {
          echo "################################################################"
          echo "# [$shape] $sort_name · $scope_name · $cname"
          echo "################################################################"
          echo "--- SQL ---"; echo "$sql"
          echo "--- 시간(3회, ms) ---"; timings "$sql"
          echo "--- EXPLAIN ANALYZE ---"; q "EXPLAIN ANALYZE $sql"
          echo
        } >> "$OUT"
      done
    done
  done
done

echo "[explain] 저장: $OUT"
