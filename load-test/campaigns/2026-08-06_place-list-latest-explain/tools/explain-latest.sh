#!/usr/bin/env bash
# 최신순(LATEST) 목록 조회의 실행계획 채취 — **읽기 전용**.
#
# SELECT / EXPLAIN / EXPLAIN ANALYZE만 실행한다. 스키마도 데이터도 건드리지 않는다.
#
# SQL 본문은 PlaceListDbQueryRepository.findLatestRows가 조립하는 문자열을 그대로 옮긴 것이다 —
# 컬럼 순서·LEFT JOIN 조건·버전 스칼라 서브쿼리·EXISTS 블록·ORDER BY·LIMIT까지 같아야 운영에서
# 도는 문장과 같은 플랜이 나온다. 앱과 같은 계정(solplyuser)으로 접속한다.
#
# 케이스 10개는 같은 날 채취한 인기순(2026-08-06_place-stats-version-explain)과 **1:1 대응**한다 —
# town/태그/커서 조합을 똑같이 두어야 "정렬 축만 다르다"의 대가를 나란히 읽을 수 있다.
#
# LIMIT 11 = 기본 페이지 크기 10 + 1 (PlaceService.DEFAULT_PAGE_SIZE, hasNext 판정용 1건).
#
# 커서는 DB에서 유도한다(1페이지의 마지막 행). 못 박아 돌리려면 환경변수로 넘긴다:
#   TOWN_CURSOR_TS=... TOWN_CURSOR_ID=... CITY_CURSOR_TS=... CITY_CURSOR_ID=...
#
# 사용: tools/explain-latest.sh [라벨]      (기본 라벨: baseline)
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/2026-08-06_place-list-latest-explain/

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

# --- 파라미터 (인기순 채취와 동일) --------------------------------------------
TOWN="301"
CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
LIMIT="${LIMIT:-11}"
MAIN_TAG="1"
SUB_A="7,8,9,10"
SUB_B="11,12,13,14,15,16"

# 커서 = "1페이지의 마지막 행" (11번째는 hasNext 판정에만 쓰고 버린다).
derive_cursor() {   # derive_cursor <town 목록>  → "<created_at>#<id>"
  mysql_n "SELECT CONCAT(created_at, '#', id) FROM (
             SELECT p.created_at, p.id FROM places p
             WHERE p.town_id IN ($1) AND p.active = 1
             ORDER BY p.created_at DESC, p.id DESC LIMIT $((LIMIT - 1))) x
           ORDER BY created_at ASC, id ASC LIMIT 1"
}
if [ -z "${TOWN_CURSOR_TS:-}" ]; then
  raw="$(derive_cursor "$TOWN")"; TOWN_CURSOR_TS="${raw%#*}"; TOWN_CURSOR_ID="${raw##*#}"
fi
if [ -z "${CITY_CURSOR_TS:-}" ]; then
  raw="$(derive_cursor "$CITY")"; CITY_CURSOR_TS="${raw%#*}"; CITY_CURSOR_ID="${raw##*#}"
fi

# --- 문장 조립 ----------------------------------------------------------------
sel() { echo "SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
      AND ps.version = (SELECT current_generation
                          FROM place_stats_meta WHERE id = 1)
WHERE p.town_id IN ($1)
  AND p.active = 1"; }

tag_main="  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = p.id AND t.id = $MAIN_TAG AND t.active = 1)"
tag_a="  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = p.id AND t.id IN ($SUB_A) AND t.active = 1)"
tag_b="  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = p.id AND t.id IN ($SUB_B) AND t.active = 1)"
cursor() { echo "  AND (p.created_at < '$1'
       OR (p.created_at = '$1' AND p.id < $2))"; }
ORDER="ORDER BY p.created_at DESC, p.id DESC LIMIT $LIMIT"

Q1="$(sel "$TOWN")
$ORDER"
Q2="$(sel "$TOWN")
$(cursor "$TOWN_CURSOR_TS" "$TOWN_CURSOR_ID")
$ORDER"
Q3="$(sel "$CITY")
$ORDER"
Q4="$(sel "$CITY")
$(cursor "$CITY_CURSOR_TS" "$CITY_CURSOR_ID")
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
Q7="$(sel "$CITY")
ORDER BY p.created_at DESC, p.id DESC LIMIT 2147483646"
# 경계를 찾으려고 더한 케이스 3개 — 태그 개수와 town 수를 따로 움직여 주도 테이블이 뒤집히는 임계를 가른다.
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
  echo "# 동네 1개 = $TOWN / 시 단위 = $CITY"
  echo "# 태그: main=$MAIN_TAG subA=($SUB_A) subB=($SUB_B)"
  echo "# 커서(동네) created_at=$TOWN_CURSOR_TS id=$TOWN_CURSOR_ID"
  echo "# 커서(시)   created_at=$CITY_CURSOR_TS id=$CITY_CURSOR_ID"
  echo "# LIMIT = $LIMIT (기본 페이지 10 + hasNext 1)"
  echo "================================================================"
  echo "--- 데이터 규모 ---"
  mysql_t "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
                  (SELECT COUNT(*) FROM place_tag)  AS place_tag_rows,
                  (SELECT COUNT(*) FROM place_stats) AS place_stats_rows;"
  mysql_t "SELECT version, COUNT(*) AS rows_cnt FROM place_stats GROUP BY version ORDER BY version;"
  echo "--- created_at 분포 (정렬 축의 타이브레이크 빈도) ---"
  mysql_t "SELECT COUNT(*) AS rows_cnt, COUNT(DISTINCT created_at) AS distinct_ts,
                  MIN(created_at) AS oldest, MAX(created_at) AS newest
             FROM places WHERE town_id IN ($CITY) AND active = 1;"
  echo "--- 인덱스 ---"
  mysql_t "SHOW INDEX FROM places;"
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
