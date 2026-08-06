#!/usr/bin/env bash
# 최신순 정렬 인덱스의 형태를 가른다 — 현행 / ASC / DESC 세 형상을 같은 케이스로 잰다.
#
# 정렬은 ORDER BY p.created_at DESC, p.id DESC다. 세컨더리 인덱스 뒤에는 PK가 오름차순으로 붙으므로
#   ASC  (town_id, active, created_at)      → 역방향 스캔이 created_at DESC, id DESC를 낸다  ← 일치
#   DESC (town_id, active, created_at DESC) → 정방향이 created_at DESC, id ASC를 낸다        ← 어긋남
# 이 차이가 실제로 filesort 유무를 가르는지 확인하는 것이 이 채취의 목적이다.
#
# ⚠️ 인덱스를 만들고 지운다 — 읽기 전용이 아니다. 마지막에 V31 적용 전 상태로 되돌린다.
#
# 사용: tools/explain-latest-index.sh [라벨]
set -euo pipefail
cd "$(dirname "$0")/.."
LABEL="${1:-baseline}"
CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
OUT="results/explain/$LABEL.txt"; mkdir -p results/explain

q(){ docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
       --default-character-set=utf8mb4 -N --raw solply_bench_db -e "$1" 2>/dev/null; }
qt(){ docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
       --default-character-set=utf8mb4 -t solply_bench_db -e "$1" 2>/dev/null; }

TOWN="301"
CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
TOWN_TS="${TOWN_CURSOR_TS:-2026-05-31 08:59:37}"; TOWN_ID="${TOWN_CURSOR_ID:-10077}"
CITY_TS="${CITY_CURSOR_TS:-2026-07-27 04:51:40}"; CITY_ID="${CITY_CURSOR_ID:-10546}"

lat(){ # $1=towns $2=커서절 $3=태그절
echo "SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
      AND ps.version = (SELECT current_generation FROM place_stats_meta WHERE id = 1)
WHERE p.town_id IN ($1)
  AND p.active = 1
$2
$3
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }
TC="  AND (p.created_at < '$TOWN_TS' OR (p.created_at = '$TOWN_TS' AND p.id < $TOWN_ID))"
CC="  AND (p.created_at < '$CITY_TS' OR (p.created_at = '$CITY_TS' AND p.id < $CITY_ID))"
TAG1="  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.tag_id = 1)"
TAG3="$TAG1
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.tag_id IN (7,8,9,10))
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.tag_id IN (11,12,13,14,15,16))"

measure(){ # measure <케이스명> <SQL>
  local name="$1" sql="$2" out=""
  q "$sql" >/dev/null || true
  for _ in 1 2 3; do
    out="$out $(q "EXPLAIN ANALYZE $sql" | head -1 | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
  done
  {
    echo "--- $name ---"
    echo "시간(3회, ms):$out"
    echo "Extra: $(q "EXPLAIN $sql" | head -1 | awk -F'\t' '{print $NF}')"
    echo "트리:"; q "EXPLAIN ANALYZE $sql" | head -6
    echo
  } >> "$OUT"
}
cases(){
  measure "동네 1곳 · 첫 페이지"      "$(lat "$TOWN" '' '')"
  measure "동네 1곳 · 커서"           "$(lat "$TOWN" "$TC" '')"
  measure "동네 18곳 · 첫 페이지"     "$(lat "$CITY" '' '')"
  measure "동네 18곳 · 커서"          "$(lat "$CITY" "$CC" '')"
  measure "동네 1곳 · 카페"           "$(lat "$TOWN" '' "$TAG1")"
  measure "동네 1곳 · 카페+옵션 2종"  "$(lat "$TOWN" '' "$TAG3")"
  measure "동네 18곳 · 카페+옵션 2종" "$(lat "$CITY" '' "$TAG3")"
}
shape(){ # shape <이름>
  { echo "################################################################"
    echo "# [$LABEL] 형상: $1"
    echo "################################################################"
    qt "SHOW INDEX FROM places WHERE Key_name LIKE 'idx_places_town%';"
  } >> "$OUT"
  cases
}

: > "$OUT"
{ echo "================================================================"
  echo "# 라벨: $LABEL / 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "# 커서(동네) $TOWN_TS / $TOWN_ID · 커서(시) $CITY_TS / $CITY_ID"
  echo "# LIMIT 11 = 첫 페이지 10 + hasNext 1"
  echo "================================================================"; } >> "$OUT"

# ① 현행 — (town_id, active)만
q "CREATE INDEX idx_places_town_active ON places (town_id, active)" || true
q "DROP INDEX idx_places_town_active_created ON places" || true
shape "현행 (town_id, active)"

# ② ASC — V31이 채택한 형태
q "CREATE INDEX idx_places_town_active_created ON places (town_id, active, created_at)"
q "DROP INDEX idx_places_town_active ON places"
shape "ASC (town_id, active, created_at)  ← V31"

# ③ DESC — V10이 만들었던 형태
q "CREATE INDEX idx_try_desc ON places (town_id, active, created_at DESC)"
q "DROP INDEX idx_places_town_active_created ON places"
shape "DESC (town_id, active, created_at DESC)"

# 마무리 — V31 **적용 전** 상태로 되돌린다.
# 벤치 DB의 flyway_schema_history를 손대지 않기 위해서다. 여기서 인덱스를 남기면 다음 앱 기동 때
# Flyway가 V31을 실행하다 "Duplicate key name"으로 부팅에 실패한다. 스키마는 앱이 올리게 둔다.
q "CREATE INDEX idx_places_town_active ON places (town_id, active)"
q "DROP INDEX idx_try_desc ON places"
{ echo "################################################################"
  echo "# 종료 시 인덱스 상태 (V31 적용 전 = 현행이어야 한다)"
  qt "SHOW INDEX FROM places WHERE Key_name LIKE 'idx_places_town%';"; } >> "$OUT"

echo "[explain] 저장: $OUT"
