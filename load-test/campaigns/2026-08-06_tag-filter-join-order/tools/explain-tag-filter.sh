#!/usr/bin/env bash
# 태그 필터 EXISTS에서 tags 조인을 뺀 전후를 나란히 채취한다 — **읽기 전용**.
#
# before = tags를 조인해 t.active = 1을 재검사하던 형태 (커밋 5e0fe94 시점)
# after  = place_tag만 보는 형태 (이번 변경)
# 두 문자열 모두 PlaceListDbQueryRepository.appendTagFilters가 만드는 것과 같아야 한다.
#
# 태그 조합 6가지 × 정렬 2가지 × 형상 2가지. 각각 워밍업 1회 후 3회 측정한다.
# 시간은 EXPLAIN ANALYZE 최상위 노드의 actual time 끝값이고, 문서에는 3회 중앙값을 싣는다.
#
# 사용: tools/explain-tag-filter.sh [라벨]      (기본 라벨: baseline)
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:-baseline}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
OUT="results/explain/$LABEL.txt"
mkdir -p results/explain

q()  { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -N --raw solply_bench_db -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
         --default-character-set=utf8mb4 -t solply_bench_db -e "$1" 2>/dev/null; }

TOWN="301"
CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
VERSION="${VERSION:-$(q "SELECT current_generation FROM place_stats_meta WHERE id = 1" | tr -d '[:space:]')}"
[ -n "$VERSION" ] && [ "$VERSION" != "NULL" ] || { echo "[explain] 현 버전이 없다" >&2; exit 1; }

# EXISTS 한 조각. $1=장소 id 컬럼 $2=태그 조건(등호 또는 IN) $3=before|after
ex() {
  if [ "$3" = "before" ]; then
    echo "  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = $1 AND t.id $2 AND t.active = 1)"
  else
    echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"
  fi
}
# $1=towns $2=조건 목록(줄바꿈 구분) $3=before|after
pop() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count, ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.version = $VERSION
  AND ps.town_id IN ($1)
$2
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat() { echo "SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
      AND ps.version = (SELECT current_generation FROM place_stats_meta WHERE id = 1)
WHERE p.town_id IN ($1)
  AND p.active = 1
$2
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

# 태그 조합 6가지. 이름 | 태그 조건들(| 구분)
#  흔한 태그 = 메인 1(1,146개 장소) / 서브A OPTION1(7~10) / 서브B OPTION2(11~16)
#  희귀 = 메인 2 + 옵션 21(185개) — 21의 부모가 2라 TagValidator를 통과한다
#  0건  = 메인 31 (붙은 장소 0개) — 활성 MAIN이라 검증을 통과하고 빈 목록이 정답이다
COMBOS=(
  "흔한 태그 1개|= 1"
  "흔한 태그 3개|= 1|IN (7,8,9,10)|IN (11,12,13,14,15,16)"
  "희귀 서브태그(185건)|= 2|IN (21)"
  "결과 0건 태그|= 31"
)

timings() {  # timings <sql>  → "t1 t2 t3"
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
  echo "# 컨테이너: $CONTAINER / DB: solply_bench_db / 계정: solplyuser"
  echo "# version = $VERSION / 동네 1개 = $TOWN / 시 단위 = $CITY"
  echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회"
  echo "================================================================"
  qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
             (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
             (SELECT COUNT(*) FROM tags) AS tags_rows,
             (SELECT SUM(active=0) FROM tags) AS inactive_tags;"
  qt "SELECT t.id, t.type, COUNT(pt.place_id) AS linked_places
        FROM tags t LEFT JOIN place_tag pt ON pt.tag_id = t.id
       WHERE t.id IN (1,2,21,31) GROUP BY t.id, t.type ORDER BY t.id;"
} >> "$OUT"

for scope_pair in "동네 1개:$TOWN" "시 단위 18개:$CITY"; do
  scope_name="${scope_pair%%:*}"; towns="${scope_pair#*:}"
  for combo in "${COMBOS[@]}"; do
    IFS='|' read -r cname c1 c2 c3 <<<"$combo"
    for sort_name in 인기순 최신순; do
      idcol="ps.place_id"; [ "$sort_name" = "최신순" ] && idcol="p.id"
      for shape in before after; do
        conds="$(ex "$idcol" "$c1" "$shape")"
        [ -n "${c2:-}" ] && conds="$conds
$(ex "$idcol" "$c2" "$shape")"
        [ -n "${c3:-}" ] && conds="$conds
$(ex "$idcol" "$c3" "$shape")"
        if [ "$sort_name" = "인기순" ]; then sql="$(pop "$towns" "$conds")"; else sql="$(lat "$towns" "$conds")"; fi
        {
          echo "################################################################"
          echo "# [$LABEL] $sort_name · $scope_name · $cname · $shape"
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
grep -B4 "^--- 시간" "$OUT" | grep "^# \[" | paste - <(grep -A1 "^--- 시간" "$OUT" | grep -v "^--- 시간" | grep -v '^--$') 2>/dev/null || true
