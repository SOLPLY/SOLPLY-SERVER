#!/usr/bin/env bash
# place_stats 버전 행(V29→V30) 인기순 조회의 실행계획 채취 — **읽기 전용**.
#
# 이 스크립트는 SELECT / EXPLAIN / EXPLAIN ANALYZE만 실행한다. 스키마도 데이터도 건드리지 않으므로
# 벤치 DB가 떠 있기만 하면 언제든 다시 돌릴 수 있다 (EXPLAIN ANALYZE는 대상 문장을 실제로
# 실행하지만 전부 SELECT다).
#
# SQL 본문은 PlaceListDbQueryRepository.findPopularRows가 조립하는 문자열을 그대로 옮긴 것이다 —
# 컬럼 순서·WHERE 절 순서·EXISTS 블록·ORDER BY·LIMIT까지 같아야 운영에서 도는 문장과 같은 플랜이
# 나온다. 앱과 같은 계정(solplyuser)으로 접속해 권한·세션 기본값 차이를 줄인다.
#
# LIMIT 11 = 기본 페이지 크기 10 + 1 (PlaceService.DEFAULT_PAGE_SIZE, hasNext 판정용 1건).
# 지난 캠페인 문서에 나오는 LIMIT 21은 부하 시나리오가 size=20을 **명시**하던 값이라 다르다.
#
# 파라미터는 기본적으로 DB에서 유도한다(버전은 place_stats_meta, 커서는 1페이지의 마지막 행).
# 유도한 값은 출력 헤더에 남으므로, 나중에 같은 값으로 못 박아 돌리려면 환경변수로 넘기면 된다:
#   VERSION=... TOWN_CURSOR_SCORE=... TOWN_CURSOR_ID=... CITY_CURSOR_SCORE=... CITY_CURSOR_ID=...
#
# 사용: tools/explain-popular.sh [라벨]      (기본 라벨: baseline)
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/2026-08-06_place-stats-version-explain/

LABEL="${1:-baseline}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
OUT_DIR="results/explain"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$LABEL.txt"

mysql_t() {   # 표 형식 (사람이 읽는 EXPLAIN)
  docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
    --default-character-set=utf8mb4 -t solply_bench_db -e "$1" 2>/dev/null
}
mysql_n() {   # 헤더 없는 원시 출력 (TREE / ANALYZE)
  docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
    --default-character-set=utf8mb4 -N --raw solply_bench_db -e "$1" 2>/dev/null
}

# --- 파라미터 -----------------------------------------------------------------
# 동네 1개는 301(장소 100), 시 단위는 201의 leaf 18개(장소 1,800) — 시드에서 가장 큰 시라
# 다중 town의 비용을 가장 크게 보여 준다.
TOWN="301"
CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
LIMIT="${LIMIT:-11}"

# 태그: 메인 1(카페) / 서브A는 OPTION1(7,8,9,10) / 서브B는 OPTION2(11~16) — 셋 다 메인 1의
# 자식이라 TagValidator를 통과하는 조합이다. EXISTS 3개가 코드가 만들 수 있는 최대다.
MAIN_TAG="1"
SUB_A="7,8,9,10"
SUB_B="11,12,13,14,15,16"

VERSION="${VERSION:-$(mysql_n "SELECT current_generation FROM place_stats_meta WHERE id = 1" | tr -d '[:space:]')}"
[ -n "$VERSION" ] && [ "$VERSION" != "NULL" ] || { echo "[explain] 현 버전이 없다 — 배치가 아직 안 돌았다" >&2; exit 1; }

# 커서는 "1페이지의 마지막 행"이다. 앱이 fetchSize 11로 읽고 11번째는 hasNext 판정에만 쓴 뒤
# 버리므로, 다음 페이지의 커서가 되는 것은 10번째 행이다 (PlaceService#listPlaces).
derive_cursor() {   # derive_cursor <town 목록>
  mysql_n "SELECT CONCAT(popular_score, ' ', place_id) FROM (
             SELECT ps.popular_score, ps.place_id FROM place_stats ps
             WHERE ps.version = $VERSION AND ps.town_id IN ($1)
             ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT $((LIMIT - 1))) x
           ORDER BY popular_score ASC, place_id DESC LIMIT 1"
}
if [ -z "${TOWN_CURSOR_SCORE:-}" ]; then
  read -r TOWN_CURSOR_SCORE TOWN_CURSOR_ID <<<"$(derive_cursor "$TOWN")"
fi
if [ -z "${CITY_CURSOR_SCORE:-}" ]; then
  read -r CITY_CURSOR_SCORE CITY_CURSOR_ID <<<"$(derive_cursor "$CITY")"
fi

# --- 문장 6개 (+ 참고 1개) -----------------------------------------------------
sel() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.version = $VERSION
  AND ps.town_id IN ($1)"; }

tag_main="  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = ps.place_id AND t.id = $MAIN_TAG AND t.active = 1)"
tag_a="  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = ps.place_id AND t.id IN ($SUB_A) AND t.active = 1)"
tag_b="  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = ps.place_id AND t.id IN ($SUB_B) AND t.active = 1)"
cursor() { echo "  AND (ps.popular_score < $1
       OR (ps.popular_score = $1 AND ps.place_id > $2))"; }
ORDER="ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT $LIMIT"

Q1="$(sel "$TOWN")
$ORDER"
Q2="$(sel "$TOWN")
$(cursor "$TOWN_CURSOR_SCORE" "$TOWN_CURSOR_ID")
$ORDER"
Q3="$(sel "$CITY")
$ORDER"
Q4="$(sel "$CITY")
$(cursor "$CITY_CURSOR_SCORE" "$CITY_CURSOR_ID")
$ORDER"
Q5="$(sel "$TOWN")
$tag_main
$ORDER"
Q6="$(sel "$CITY")
$tag_main
$tag_a
$tag_b
$ORDER"
# 참고: size·cursor를 둘 다 생략한 요청은 페이지를 나누지 않는다(pageSize = MAX_VALUE-1).
# 같은 플랜에서 LIMIT만 사라지는 셈이라 정렬 대상이 시 전체로 늘어난다.
Q7="$(sel "$CITY")
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 2147483646"

# 경계를 찾으려고 더한 케이스 3개. case6에서 주도 테이블이 place_stats → place_tag로 뒤집히는 것을 보고 추가했다 —
# "태그가 붙으면 뒤집힌다"인지 "EXISTS가 여러 개일 때 뒤집힌다"인지, 동네 수가 영향을 주는지를
# 가르려면 태그 개수와 town 수를 따로 움직여 봐야 한다.
Q8="$(sel "$CITY")
$tag_main
$ORDER"
Q9="$(sel "$CITY")
$tag_main
$tag_a
$ORDER"
Q10="$(sel "$TOWN")
$tag_main
$tag_a
$tag_b
$ORDER"

run() {   # run <케이스명> <SQL>
  local name="$1" sql="$2"
  mysql_n "$sql" >/dev/null || true          # 워밍업 — 첫 실행의 디스크 읽기를 계측에서 뺀다
  {
    echo "################################################################"
    echo "# [$LABEL] $name"
    echo "################################################################"
    echo "--- SQL ---"
    echo "$sql"
    echo "--- EXPLAIN (traditional) ---"
    mysql_t "EXPLAIN $sql;"
    echo "--- EXPLAIN FORMAT=TREE ---"
    mysql_n "EXPLAIN FORMAT=TREE $sql;"
    echo "--- EXPLAIN ANALYZE ---"
    mysql_n "EXPLAIN ANALYZE $sql;"
    echo
  } >> "$OUT"
}

: > "$OUT"
{
  echo "================================================================"
  echo "# 라벨: $LABEL / 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 컨테이너: $CONTAINER / DB: solply_bench_db / 계정: solplyuser"
  echo "# version(current_generation) = $VERSION"
  echo "# 동네 1개 = $TOWN / 시 단위 = $CITY"
  echo "# 태그: main=$MAIN_TAG subA=($SUB_A) subB=($SUB_B)"
  echo "# 커서(동네) score=$TOWN_CURSOR_SCORE id=$TOWN_CURSOR_ID"
  echo "# 커서(시)   score=$CITY_CURSOR_SCORE id=$CITY_CURSOR_ID"
  echo "# LIMIT = $LIMIT (기본 페이지 10 + hasNext 1)"
  echo "================================================================"
  echo "--- 데이터 규모 ---"
  mysql_t "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
                  (SELECT COUNT(*) FROM bookmarks)  AS bookmarks,
                  (SELECT COUNT(*) FROM place_reviews) AS reviews,
                  (SELECT COUNT(*) FROM place_stats) AS place_stats_rows;"
  mysql_t "SELECT version, COUNT(*) AS rows_cnt FROM place_stats GROUP BY version ORDER BY version;"
  echo "--- 인덱스 ---"
  mysql_t "SHOW INDEX FROM place_stats;"
  echo
} >> "$OUT"

run "case1 town-first    (동네 1개 · 첫 페이지)"            "$Q1"
run "case2 town-next     (동네 1개 · 다음 페이지)"          "$Q2"
run "case3 city-first    (시 단위 18개 · 첫 페이지)"        "$Q3"
run "case4 city-next     (시 단위 18개 · 다음 페이지)"      "$Q4"
run "case5 town-tag      (동네 1개 · 메인 태그)"            "$Q5"
run "case6 city-tag3     (시 단위 18개 · 메인+서브A+서브B)" "$Q6"
run "case7 city-nolimit  (참고: 페이징 미요청 경로)"        "$Q7"
run "case8 city-tag1     (경계 확인: 시 단위 · 메인 태그만)"     "$Q8"
run "case9 city-tag2     (경계 확인: 시 단위 · 메인+서브A)"      "$Q9"
run "case10 town-tag3    (경계 확인: 동네 1개 · 메인+서브A+서브B)" "$Q10"

echo "[explain] 저장: $OUT"
