#!/usr/bin/env bash
# 기반을 고정하고 **쿼리 구조만** 세 가지로 바꿔 같은 창에서 잰다.
#
#   JOIN — 당시 구조. 태그 필터 EXISTS 안에 `place_tag JOIN tags`가 남아 있고 t.active = 1을
#          재검사한다. 그룹당 EXISTS 하나, 그룹 안은 IN 리스트.
#   A    — 조인 제거. 같은 EXISTS에서 tags 조인과 t.active = 1을 뺐다. 그 외 전부 동일.
#   B    — 읽기 모델. EXISTS를 없애고 place_stats의 그룹 비트마스크 술어로 바꿨다.
#
# 술어의 의미는 셋 다 같다 — **그룹 안 OR, 그룹 사이 AND**(확정 스펙).
#
# ────────────────────────────────────────────────────────────────────────────
# 왜 이렇게 설계했는가
# ────────────────────────────────────────────────────────────────────────────
# 묻는 것은 "조인 구조가 만든 문제를 각 해법이 얼마나 해결했는가"다. 그러려면 조인 구조 말고는
# 다 같아야 한다. 그래서 **기반(통계 테이블 형태 + 커버링 인덱스 = V32+V33)을 고정하고 쿼리
# 구조만 바꾼다.**
#
# 처음 구조를 V29 형상(버전당 행 집합 + place_stats_meta)까지 되돌리는 방법도 있었고 실제로
# 재현도 됐지만, 쓰지 않는다. 그 형상에는 조인 구조 말고도 다른 것이 얹혀 있다 — 테이블이 2배,
# PK에 version, 인덱스 선두가 version, 그리고 place_tag의 태그 방향 인덱스가 비커버링(V33 이전).
# 그것들까지 함께 되돌리면 사다리의 각 칸이 "조인을 없앤 몫"이 아니라 "그동안 쌓인 기반 개선분
# 전체"를 재게 된다. 두 질문은 다르고, 지금 묻는 것은 앞쪽이다.
#
# ⇒ 그래서 JOIN 열과 A 열은 **EXISTS 안의 문자열 말고는 바이트 동일한 SQL**이다. 그 차이만
#   남기는 것이 이 캠페인의 전부다. B는 EXISTS 자체가 사라지므로 문장이 달라지지만, FROM과
#   정렬·LIMIT은 현행 구현 그대로다.
#
# 창도 통일한다. 세 열이 같은 컨테이너·같은 데이터·같은 시간대에서 나와야 캠페인 사이의
# 드리프트(옵티마이저 비용 계수·버퍼풀 상주율)가 사다리에 섞이지 않는다.
#
# ⚠️ V34를 되돌렸다가 되돌린다. 사용자의 명시적 승인 아래 돌린다. place_stats의 행은 건드리지
#    않는다 — 컬럼 두 개(created_at·tag_bitmask)를 빼고 인덱스를 V32 형태로 되돌릴 뿐이고,
#    둘 다 places·place_tag에서 재계산되는 파생값이다.
#    EXIT 트랩이 어떤 경로로 끝나든 현행(V34) 형상으로 복원한다.
# ⚠️ flyway_schema_history는 건드리지 않는다. 되돌린 창 동안 앱을 띄우면 안 된다.
#
# 사용:
#   bash bench/three-shapes.sh            # 전 과정
#   bash bench/three-shapes.sh restore    # 현행 형상 복원 + 검증만 (중단 수습)
#   bash bench/three-shapes.sh verify     # 검증만
set -euo pipefail
cd "$(dirname "$0")/.."

CONTAINER="${BENCH_MYSQL_CONTAINER:-solply-bench-mysql}"
DB="solply_bench_db"
OUTDIR="results/explain"
CSV_J="results/summary-join-or.csv"
CSV_A="results/summary-a-or.csv"
CSV_B="results/summary-b-or.csv"
ORIG_A="../2026-08-11_tag-bitmask-read-model/results/summary-a.csv"
HDR="$OUTDIR/_environment.txt"
MODE="${1:-all}"
mkdir -p "$OUTDIR"

q() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw "$DB" -e "$1" 2>/dev/null; }
qt() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t "$DB" -e "$1" 2>/dev/null; }
ddl() { docker exec -i "$CONTAINER" mysql -usolplyuser -psolplyuserpwd \
          --default-character-set=utf8mb4 "$DB" -e "$1"; }

# ---------- 지역 ---------- 원 캠페인(explain-a-grid.sh)과 동일. 동네 하나가 정확히 100곳이다.
TOWN1="301"
TOWN4="301,302,303,304"
CITY18="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
REGIONS=("town1:$TOWN1" "town4:$TOWN4" "city18:$CITY18")
RUNS_DEFAULT=3
RUNS_HEAVY=7          # JOIN 열의 무거운 칸 — 20ms대는 3회 중앙값이 회차마다 갈린다
RUNS=$RUNS_DEFAULT

# ---------- 태그 축 ---------- 원 캠페인의 7축 그대로
# 셀id|그룹 구조(';'=그룹 경계, ','=그룹 안 대안)|그룹별 마스크(';' 구분)
# 마스크의 비트 자리 = tag id. 그룹 마스크는 그 그룹 태그들의 OR 합이다.
#   카페 1 → 2 · 이색공간 5 → 32 · 음식 2 → 4 · 바/술집 21 → 2097152
#   OPTION1 {7,8,9,10}  → 128+256+512+1024 = 1920
#   OPTION2 {11,...,16} → 2048+4096+8192+16384+32768+65536 = 129024
#   합성 40 → 1099511627776 · 합성 41 → 2199023255552
TAGCELLS=(
  "notag||"
  "maincafe|1|2"
  "hotcombo|1;7,8,9,10;11,12,13,14,15,16|2;1920;129024"
  "mainrare|5|32"
  "mainsubrare|2;21|4;2097152"
  "synth20|40|1099511627776"
  "zero|41|2199023255552"
)
CURSOR_CELL="hotcombo"
# JOIN 열에서 7회로 잴 칸. 태그 후보가 크고 오추정이 걸리면 20ms대까지 가는 칸들이다.
HEAVY_CELLS="maincafe hotcombo mainrare"

# A 열 재현성 확인에 쓸 칸 — 어제 기록(원 캠페인 summary-a.csv)과 같은 SQL인 칸들이다.
# 형식: 지역|셀id|정렬
A_RECHECK=("city18|notag|popular" "city18|hotcombo|popular" "city18|mainsubrare|latest")

# ============================================================================
# 술어 — 세 구조의 유일한 차이
# ============================================================================
# JOIN: tags 조인이 낀 EXISTS. 당시 appendTagFilters가 만들던 문자열 그대로다.
ex_join() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = $1 AND t.id $2 AND t.active = 1)"; }
# A: 조인 제거. place_tag만 본다. 위와 이 줄 말고는 SQL이 바이트 동일하다.
ex_a() { echo "  AND EXISTS (SELECT 1 FROM place_tag pt
               WHERE pt.place_id = $1 AND pt.tag_id $2)"; }
# 그룹당 EXISTS 하나, 그룹 안은 IN 리스트. 단일 태그 그룹은 등호로 쓴다 (당시 스크립트와 같다).
conds_exists() {  # $1=idcol $2=groups $3=join|a
  local g pred
  if [ -n "$2" ]; then
    local IFS=';'
    for g in $2; do
      case "$g" in
        *,*) pred="IN ($g)" ;;
        *)   pred="= $g" ;;
      esac
      if [ "$3" = "join" ]; then ex_join "$1" "$pred"; else ex_a "$1" "$pred"; fi
    done
  fi
}
# B: 그룹 마스크 술어. 현행 PlaceListDbQueryRepository와 같다 — 그룹당 하나, `!= 0`.
conds_mask() {  # $1=masks(';' 구분)
  local m
  if [ -n "$1" ]; then
    local IFS=';'
    for m in $1; do echo "  AND (ps.tag_bitmask & $m) != 0"; done
  fi
}

# ============================================================================
# SQL 골격
# ============================================================================
# JOIN·A 공통 — V32+V33 기반. 인기순은 place_stats 단독, 최신순은 places 기준 + 표시용 LEFT JOIN.
# (원 캠페인 explain-a-grid.sh의 pop()/lat()와 바이트 동일)
pop_ja() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2$3
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat_ja() { echo "SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0),
       COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps
       ON ps.place_id = p.id
WHERE p.town_id IN ($1)
  AND p.active = 1
$2$3
ORDER BY p.created_at DESC, p.id DESC LIMIT 11"; }

# B — 현행. 두 정렬 모두 place_stats 단독이다.
pop_b() { echo "SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2$3
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"; }
lat_b() { echo "SELECT ps.place_id, ps.created_at, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($1)
$2$3
ORDER BY ps.created_at DESC, ps.place_id DESC LIMIT 11"; }

# 매치 수 · 11번째 행(2페이지 커서)
cnt_pop()   { q "SELECT COUNT(*) FROM place_stats ps WHERE ps.town_id IN ($1)
  AND ps.score_calculated_at IS NOT NULL
$2"; }
cnt_lat_p() { q "SELECT COUNT(*) FROM places p WHERE p.town_id IN ($1) AND p.active = 1
$2"; }
cnt_lat_b() { q "SELECT COUNT(*) FROM place_stats ps WHERE ps.town_id IN ($1)
$2"; }
key11_pop()   { q "SELECT CONCAT(ps.popular_score, '|', ps.place_id) FROM place_stats ps
WHERE ps.town_id IN ($1) AND ps.score_calculated_at IS NOT NULL
$2
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 1 OFFSET 10"; }
key11_lat_p() { q "SELECT CONCAT(DATE_FORMAT(p.created_at, '%Y-%m-%d %H:%i:%s'), '|', p.id)
FROM places p WHERE p.town_id IN ($1) AND p.active = 1
$2
ORDER BY p.created_at DESC, p.id DESC LIMIT 1 OFFSET 10"; }
key11_lat_b() { q "SELECT CONCAT(DATE_FORMAT(ps.created_at, '%Y-%m-%d %H:%i:%s'), '|', ps.place_id)
FROM place_stats ps WHERE ps.town_id IN ($1)
$2
ORDER BY ps.created_at DESC, ps.place_id DESC LIMIT 1 OFFSET 10"; }

# 커서 술어 — 앱의 findPopularRows / findLatestRows 문장과 같다.
# 인기 점수에 E0을 붙여 double 리터럴로 만드는 것은 앱이 커서 sortKey를 double로 바인딩하고
# MySQL이 DECIMAL을 DOUBLE로 올려 비교하기 때문이다. 경계 판정을 같은 타입으로 맞춘다.
pop_cursor()   { echo "
  AND (ps.popular_score < ${1}E0
       OR (ps.popular_score = ${1}E0 AND ps.place_id > $2))"; }
lat_cursor_p() { echo "
  AND (p.created_at < '$1'
       OR (p.created_at = '$1' AND p.id < $2))"; }
lat_cursor_b() { echo "
  AND (ps.created_at < '$1'
       OR (ps.created_at = '$1' AND ps.place_id < $2))"; }

# ---------- 판정 함수 ----------
first_node_line() { printf '%s\n' "$1" | grep -E '^[[:space:]]*-> ' | grep -m1 ' on '; }
driving_of()   { first_node_line "$1" | sed -n 's/.* on \([^ ]*\).*/\1/p'; }
driving_rows() { first_node_line "$1" | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
top_rows()     { printf '%s\n' "$1" | head -1 | grep -o 'actual time=[^)]*' \
                   | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }
# 추정/실제 대조는 Limit을 뺀 최상위 비용 노드에서 읽는다 — Limit의 rows=는 LIMIT 값으로 잘린
# 수라 카디널리티 추정이 아니다.
est_line()  { printf '%s\n' "$1" | grep '(cost=' | grep -vE '^[[:space:]]*-> Limit' | head -1; }
est_rows()  { est_line "$1" | sed 's/(actual.*//' | grep -o 'rows=[0-9.e+-]*' | tail -1 | cut -d= -f2; }
pre_rows()  { est_line "$1" | grep -o 'actual time=[^)]*' | grep -o 'rows=[0-9.e+-]*' | cut -d= -f2; }
# tags 노드가 트리에 남아 있는지 — JOIN 열에서 오추정 구조가 재현됐는지의 직접 증거다.
# t.id가 상수 등호면 PK 등호라 조인이 접혀 사라지고, IN 리스트면 범위라 남아서 해시 조인이 된다.
tags_nodes() { printf '%s\n' "$1" | grep -cE '^[[:space:]]*-> .* on t using' || true; }
cartesian_of() { printf '%s\n' "$1" | grep -c 'Inner hash join (no condition)' || true; }
join_nodes() { printf '%s\n' "$1" | grep -cE '^[[:space:]]*-> (Nested loop|Hash (semi)?join|Inner hash join)' || true; }
semijoin_of() {
  local s=""
  printf '%s\n' "$1" | grep -qi 'FirstMatch'                 && s="${s}firstmatch+"
  printf '%s\n' "$1" | grep -qi 'Materialize'                && s="${s}materialize+"
  printf '%s\n' "$1" | grep -qiE 'Remove duplicate|weedout'   && s="${s}weedout+"
  printf '%s\n' "$1" | grep -qi 'Loose ?[Ss]can'              && s="${s}loosescan+"
  echo "${s:-none}" | sed 's/+$//'
}
shape_of() {
  local s=""
  printf '%s\n' "$1" | grep -qE '^[[:space:]]*-> Sort' && s="${s}sort+"
  printf '%s\n' "$1" | grep -qi 'FirstMatch'           && s="${s}firstmatch+"
  printf '%s\n' "$1" | grep -qi 'Materialize'          && s="${s}materialize+"
  printf '%s\n' "$1" | grep -qiE 'Remove duplicate'    && s="${s}weedout+"
  printf '%s\n' "$1" | grep -qi 'Nested loop'          && s="${s}nl+"
  echo "${s%+}"
}
agreeN() {
  local first="$1" v
  for v in "$@"; do [ "$v" = "$first" ] || { local IFS='/'; echo "*$*"; return 0; }; done
  echo "$first"
}
medianN() { local n=$#; printf '%s\n' "$@" | sort -g | sed -n "$(( n / 2 + 1 ))p"; }

# ============================================================================
# 형상 전환 — V34만 오간다. place_stats의 행은 건드리지 않는다.
# ============================================================================
SHAPE="V34"

to_v33() {
  echo "[3shapes] === 형상 → V32+V33 (V34 되돌림) ==="
  # 컬럼이 인덱스 말단에 실려 있어 인덱스를 먼저 정리해야 컬럼을 지울 수 있다.
  ddl "DROP INDEX idx_place_stats_town_created ON place_stats;"
  ddl "DROP INDEX idx_place_stats_town_score ON place_stats;"
  # V32__place_stats_single_row_and_split_batches.sql의 정의 그대로 (tag_bitmask 없음)
  ddl "CREATE INDEX idx_place_stats_town_score
         ON place_stats (town_id, popular_score DESC, place_id,
                         bookmark_count, review_count, avg_rating, score_calculated_at);"
  ddl "ALTER TABLE place_stats DROP COLUMN tag_bitmask, DROP COLUMN created_at;"
  ddl "ANALYZE TABLE place_stats, place_tag, tags, places;"
  SHAPE="V33"
  echo "[3shapes] V32+V33 형상 완료"
}

to_v34() {
  echo "[3shapes] === 형상 → V34 (현행) ==="
  # V34__place_stats_read_model.sql의 SQL 순서 그대로
  ddl "ALTER TABLE place_stats
         ADD COLUMN created_at  DATETIME NULL COMMENT '장소 생성일. places.created_at의 비정규화 사본',
         ADD COLUMN tag_bitmask BIGINT   NOT NULL DEFAULT 0 COMMENT '이 장소가 가진 태그의 비트 합집합. 비트 자리 = tag id (0..62)';"
  ddl "UPDATE place_stats ps JOIN places p ON p.id = ps.place_id SET ps.created_at = p.created_at;"
  ddl "UPDATE place_stats ps
         JOIN (SELECT pt.place_id, BIT_OR(1 << pt.tag_id) AS mask
                 FROM place_tag pt GROUP BY pt.place_id) t ON t.place_id = ps.place_id
          SET ps.tag_bitmask = t.mask;"
  ddl "ALTER TABLE place_stats
         MODIFY COLUMN created_at DATETIME NOT NULL COMMENT '장소 생성일. places.created_at의 비정규화 사본';"
  ddl "DROP INDEX idx_place_stats_town_score ON place_stats;"
  ddl "CREATE INDEX idx_place_stats_town_score
         ON place_stats (town_id, popular_score DESC, place_id,
                         bookmark_count, review_count, avg_rating, score_calculated_at, tag_bitmask);"
  ddl "CREATE INDEX idx_place_stats_town_created
         ON place_stats (town_id, created_at, place_id,
                         tag_bitmask, bookmark_count, review_count, avg_rating);"
  ddl "ANALYZE TABLE place_stats, place_tag, tags, places;"
  SHAPE="V34"
  echo "[3shapes] V34(현행) 형상 완료"
}

cleanup() {
  local rc=$?
  if [ "$SHAPE" != "V34" ]; then
    echo "[3shapes] ⚠️ 비정상 종료(rc=$rc) — 현행 형상 복원을 시도한다"
    # 합성 태그를 먼저 지운다 — V34 백필이 마스크에 40·41 비트를 새기기 전에.
    q "DELETE FROM place_tag WHERE tag_id IN (40,41)" >/dev/null 2>&1 || true
    q "DELETE FROM tags      WHERE id     IN (40,41)" >/dev/null 2>&1 || true
    to_v34 || echo "[3shapes] ⚠️⚠️ 복원 실패. 수동 복구: bash bench/three-shapes.sh restore"
    do_verify "비정상 종료 후" || true
  fi
}
trap cleanup EXIT

do_verify() {
  local label="${1:-검증}"
  echo "[3shapes] === 복원 검증 ($label) ==="
  local mm nul dif rows synth
  mm="$(q "SELECT COUNT(*) FROM place_stats ps
             LEFT JOIN (SELECT place_id, BIT_OR(1<<tag_id) AS m FROM place_tag GROUP BY place_id) t
                    ON t.place_id = ps.place_id
            WHERE ps.tag_bitmask <> COALESCE(t.m, 0)" | tr -d '[:space:]')"
  nul="$(q "SELECT COUNT(*) FROM place_stats WHERE created_at IS NULL" | tr -d '[:space:]')"
  dif="$(q "SELECT COUNT(*) FROM place_stats ps JOIN places p ON p.id = ps.place_id
             WHERE ps.created_at <> p.created_at" | tr -d '[:space:]')"
  rows="$(q "SELECT COUNT(*) FROM place_stats" | tr -d '[:space:]')"
  synth="$(q "SELECT (SELECT COUNT(*) FROM tags WHERE id IN (40,41))
                   + (SELECT COUNT(*) FROM place_tag WHERE tag_id IN (40,41))
                   + (SELECT COUNT(*) FROM place_stats WHERE (tag_bitmask & ((1<<40)|(1<<41))) <> 0)" | tr -d '[:space:]')"
  local sql t1 t2 t3 med
  sql="SELECT ps.place_id, ps.popular_score, ps.bookmark_count,
       ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.town_id IN ($CITY18)
  AND ps.score_calculated_at IS NOT NULL
  AND (ps.tag_bitmask & 2) != 0
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 11"
  q "$sql" >/dev/null || true
  t1="$(q "EXPLAIN ANALYZE $sql" | head -1 | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
  t2="$(q "EXPLAIN ANALYZE $sql" | head -1 | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
  t3="$(q "EXPLAIN ANALYZE $sql" | head -1 | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
  med="$(medianN "$t1" "$t2" "$t3")"
  {
    echo
    echo "================================================================"
    echo "# 복원 검증 ($label): $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "# ① 스키마 = 현행(V34) 형상 / ② tag_bitmask 전수 재계산 / ③ created_at"
    echo "# ④ 현행 술어 재실행 / ⑤ 합성 태그 잔재"
    echo "================================================================"
    qt "SELECT column_name, column_type, is_nullable FROM information_schema.columns
         WHERE table_schema='$DB' AND table_name='place_stats' ORDER BY ordinal_position;"
    qt "SELECT table_name, index_name, seq_in_index, column_name, collation
          FROM information_schema.statistics
         WHERE table_schema='$DB' AND table_name IN ('place_stats','place_tag')
         ORDER BY table_name, index_name, seq_in_index;"
    echo "mask_mismatch=$mm created_at_null=$nul created_at_mismatch=$dif ps_rows=$rows synth_leftover=$synth"
    echo "검증④ 재실행: $t1 $t2 $t3 → median ${med}ms"
  } >> "$HDR"
  echo "[3shapes] ② mask=$mm ③ ca_null=$nul/mismatch=$dif ④ ${med}ms ⑤ synth잔재=$synth (ps_rows=$rows)"
}

# ============================================================================
# 합성 태그
# ============================================================================
seed_synth() {
  local total; total="$(q "SELECT COUNT(*) FROM places WHERE active = 1" | tr -d '[:space:]')"
  q "DELETE FROM place_tag WHERE tag_id IN (40,41)" >/dev/null
  q "DELETE FROM tags WHERE id IN (40,41)" >/dev/null
  q "INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
     VALUES (40, '합성메인0020', 'MAIN', NULL, 1, 'PLACE')" >/dev/null
  # Bresenham 등간격 — 전 구간에서 정확히 20개가 고르게 뽑힌다(RAND() 아님, 재현 가능)
  q "INSERT INTO place_tag (place_id, tag_id)
     SELECT t.place_id, 40 FROM (
       SELECT id AS place_id, ROW_NUMBER() OVER (ORDER BY id) AS rn
         FROM places WHERE active = 1
     ) t
     WHERE FLOOR(t.rn * 20 / $total) > FLOOR((t.rn - 1) * 20 / $total)" >/dev/null
  q "INSERT INTO tags (id, name, type, parent_id, active, tag_usage)
     VALUES (41, '합성메인0000', 'MAIN', NULL, 1, 'PLACE')" >/dev/null
  ddl "ANALYZE TABLE tags, place_tag;"
  echo "[3shapes] 합성 태그 시드: id40 $(q "SELECT COUNT(*) FROM place_tag WHERE tag_id=40" | tr -d '[:space:]')곳 / id41 0곳"
}
rollback_synth() {
  q "DELETE FROM place_tag WHERE tag_id IN (40,41)" >/dev/null
  q "DELETE FROM tags WHERE id IN (40,41)" >/dev/null
  # 마스크 전수 재계산. LEFT JOIN + COALESCE라 태그가 하나도 없는 행도 0으로 바로잡는다 —
  # V34의 백필(INNER JOIN)은 그 행을 건드리지 않아 잔재가 남을 수 있다.
  if [ "$SHAPE" = "V34" ]; then
    ddl "UPDATE place_stats ps
           LEFT JOIN (SELECT place_id, BIT_OR(1 << tag_id) AS m FROM place_tag GROUP BY place_id) t
                  ON t.place_id = ps.place_id
            SET ps.tag_bitmask = COALESCE(t.m, 0);"
  fi
  ddl "ANALYZE TABLE tags, place_tag, place_stats;"
  echo "[3shapes] 합성 태그 롤백: tags $(q "SELECT COUNT(*) FROM tags WHERE id IN (40,41)" | tr -d '[:space:]') / place_tag $(q "SELECT COUNT(*) FROM place_tag WHERE tag_id IN (40,41)" | tr -d '[:space:]')"
}

# ============================================================================
# 채취
# ============================================================================
CSV=""
csv_init() {
  : > "$1"
  # tags_nodes / cartesian — JOIN 열에서 오추정 구조가 재현됐는지의 직접 증거다.
  #   tags_nodes>0 : t.id가 IN 리스트라 조인이 접히지 않고 트리에 남았다
  #   cartesian>0  : 두 태그 축이 조건 없는 해시 조인으로 곱해졌다(= 추정이 무너지는 자리)
  # ⚠️ tag_expr에는 ','를 쓰지 않는다 — CSV 필드가 밀린다. ','는 '+'로 바꿔 적는다.
  echo "phase,form,sort,region,tagcell,tag_expr,groups,page,driving_table,driving_rows,estimated_rows,prelimit_rows,top_rows,match_rows,tags_nodes,cartesian,join_nodes,semijoin,shape,n_runs,runs_ms,median_ms" >> "$1"
}

# run_cell <phase> <form> <sort> <region> <towns> <cellid> <조건> <커서> <태그표기> <그룹수> <page> <접두>
run_cell() {
  local phase="$1" form="$2" sort_name="$3" region="$4" towns="$5" cid="$6"
  local cond="$7" cur="$8" texpr="$9" ngrp="${10}" page="${11}" pfx="${12}"
  local sql match
  if [ "$form" = "B" ]; then
    if [ "$sort_name" = "popular" ]; then sql="$(pop_b "$towns" "$cond" "$cur")"; match="$(cnt_pop "$towns" "$cond")"
    else sql="$(lat_b "$towns" "$cond" "$cur")"; match="$(cnt_lat_b "$towns" "$cond")"; fi
  else
    if [ "$sort_name" = "popular" ]; then sql="$(pop_ja "$towns" "$cond" "$cur")"; match="$(cnt_pop "$towns" "$cond")"
    else sql="$(lat_ja "$towns" "$cond" "$cur")"; match="$(cnt_lat_p "$towns" "$cond")"; fi
  fi

  local f="$OUTDIR/${pfx}-${region}-${cid}-${sort_name}"
  [ "$page" = "p2" ] && f="${f}-p2"
  f="${f}.txt"
  {
    echo "################################################################"
    echo "# $form · $sort_name · $region · $cid · $page"
    echo "# 태그 그룹: [${texpr}] (그룹 ${ngrp}개, 그룹 안 OR · 그룹 사이 AND)"
    echo "# 기반 형상: $SHAPE"
    echo "# towns: $towns / 1페이지 기준 매치 행 수: $match"
    echo "# 채취: $(date '+%Y-%m-%d %H:%M:%S')"
    echo "################################################################"
    echo "--- SQL ---"; echo "$sql"
  } > "$f"

  q "$sql" >/dev/null || true
  local times=() drvs=() drows=() erows=() prows=() trows=() tn=() ca=() jn=() sj=() shapes=()
  local run tree t
  for run in $(seq 1 "$RUNS"); do
    tree="$(q "EXPLAIN ANALYZE $sql")"
    { echo; echo "--- EXPLAIN ANALYZE (run $run) ---"; echo "$tree"; } >> "$f"
    t="$(printf '%s\n' "$tree" | head -1 \
          | grep -o 'actual time=[0-9.e-]*\.\.[0-9.e-]*' | sed 's/.*\.\.//')"
    times+=("${t:-NA}")
    drvs+=("$(driving_of "$tree" || echo none)")
    drows+=("$(driving_rows "$tree" || echo NA)")
    erows+=("$(est_rows "$tree" || echo NA)")
    prows+=("$(pre_rows "$tree" || echo NA)")
    trows+=("$(top_rows "$tree" || echo NA)")
    tn+=("$(tags_nodes "$tree")")
    ca+=("$(cartesian_of "$tree")")
    jn+=("$(join_nodes "$tree")")
    sj+=("$(semijoin_of "$tree")")
    shapes+=("$(shape_of "$tree")")
  done
  local drvtab drv_rows est_r pre_r top_r tnn can jnn sjn shp med runs_str
  drvtab="$(agreeN "${drvs[@]}")";   drv_rows="$(agreeN "${drows[@]}")"
  est_r="$(agreeN "${erows[@]}")";   pre_r="$(agreeN "${prows[@]}")"
  top_r="$(agreeN "${trows[@]}")";   tnn="$(agreeN "${tn[@]}")"
  can="$(agreeN "${ca[@]}")";        jnn="$(agreeN "${jn[@]}")"
  sjn="$(agreeN "${sj[@]}")";        shp="$(agreeN "${shapes[@]}")"
  med="$(medianN "${times[@]}")"
  runs_str="$(IFS=';'; echo "${times[*]}")"
  echo "$phase,$form,$sort_name,$region,$cid,\"$texpr\",$ngrp,$page,$drvtab,$drv_rows,$est_r,$pre_r,$top_r,$match,$tnn,$can,$jnn,$sjn,$shp,$RUNS,$runs_str,$med" >> "$CSV"
  echo "[$form] $sort_name/$region/$cid/$page drv=$drvtab drvrows=$drv_rows est=$est_r pre=$pre_r match=$match tags=$tnn cart=$can sj=$sjn med=${med}ms"
  LAST_MED="$med"; LAST_DRV="$drvtab"
}

# 그리드 한 벌. $1=phase $2=form(JOIN|A|B) $3=파일접두
run_grid() {
  local phase="$1" form="$2" pfx="$3"
  local rp region towns cellspec cid groups masks ngrp texpr sort_name idcol cond
  local key sk sid cur

  for rp in "${REGIONS[@]}"; do
    region="${rp%%:*}"; towns="${rp#*:}"
    for cellspec in "${TAGCELLS[@]}"; do
      IFS='|' read -r cid groups masks <<<"$cellspec"
      if [ -z "$groups" ]; then ngrp=0; texpr="(무태그)"
      else ngrp="$(printf '%s' "$groups" | tr ';' '\n' | wc -l | tr -d ' ')"; texpr="$(printf '%s' "$groups" | tr ',' '+')"; fi
      # JOIN 열의 무거운 칸만 7회 — 20ms대는 3회 중앙값이 회차마다 갈린다
      RUNS=$RUNS_DEFAULT
      if [ "$form" = "JOIN" ] && printf '%s' " $HEAVY_CELLS " | grep -q " $cid "; then RUNS=$RUNS_HEAVY; fi
      for sort_name in popular latest; do
        idcol="ps.place_id"
        [ "$form" != "B" ] && [ "$sort_name" = "latest" ] && idcol="p.id"
        case "$form" in
          JOIN) cond="$(conds_exists "$idcol" "$groups" join)" ;;
          A)    cond="$(conds_exists "$idcol" "$groups" a)" ;;
          B)    cond="$(conds_mask "$masks")" ;;
        esac
        run_cell "$phase" "$form" "$sort_name" "$region" "$towns" "$cid" \
                 "$cond" "" "$texpr" "$ngrp" p1 "$pfx"
      done
    done
  done
  RUNS=$RUNS_DEFAULT

  # ---- 커서 2페이지 (핫콤보 칸만) ----
  # 1페이지의 11번째 행에서 커서를 뽑는다. 매치가 11건에 못 미치면 2페이지가 존재하지 않으므로
  # 건너뛰고 사유를 CSV에 남긴다 (동네 1곳 핫콤보가 여기 해당 — 매치 10건).
  echo "[$form] === 커서 2페이지 ==="
  for cellspec in "${TAGCELLS[@]}"; do
    IFS='|' read -r cid groups masks <<<"$cellspec"
    if [ "$cid" = "$CURSOR_CELL" ]; then break; fi
  done
  ngrp="$(printf '%s' "$groups" | tr ';' '\n' | wc -l | tr -d ' ')"
  texpr="$(printf '%s' "$groups" | tr ',' '+')"
  [ "$form" = "JOIN" ] && RUNS=$RUNS_HEAVY
  for rp in "${REGIONS[@]}"; do
    region="${rp%%:*}"; towns="${rp#*:}"
    for sort_name in popular latest; do
      idcol="ps.place_id"
      [ "$form" != "B" ] && [ "$sort_name" = "latest" ] && idcol="p.id"
      case "$form" in
        JOIN) cond="$(conds_exists "$idcol" "$groups" join)" ;;
        A)    cond="$(conds_exists "$idcol" "$groups" a)" ;;
        B)    cond="$(conds_mask "$masks")" ;;
      esac
      if [ "$sort_name" = "popular" ]; then key="$(key11_pop "$towns" "$cond")"
      elif [ "$form" = "B" ]; then key="$(key11_lat_b "$towns" "$cond")"
      else key="$(key11_lat_p "$towns" "$cond")"; fi
      key="$(printf '%s' "$key" | tr -d '\r')"
      if [ -z "$key" ]; then
        echo "$phase,$form,$sort_name,$region,$cid,\"$texpr\",$ngrp,p2-skip,NA,NA,NA,NA,NA,NA,NA,NA,NA,NA,매치<11이라 2페이지 없음,0,NA,NA" >> "$CSV"
        echo "[$form] $sort_name/$region/$cid/p2 → 건너뜀 (커서 없음)"
        continue
      fi
      sk="${key%%|*}"; sid="${key##*|}"
      if [ "$sort_name" = "popular" ]; then cur="$(pop_cursor "$sk" "$sid")"
      elif [ "$form" = "B" ]; then cur="$(lat_cursor_b "$sk" "$sid")"
      else cur="$(lat_cursor_p "$sk" "$sid")"; fi
      run_cell "$phase" "$form" "$sort_name" "$region" "$towns" "$cid" \
               "$cond" "$cur" "$texpr" "$ngrp" p2 "$pfx"
    done
  done
  RUNS=$RUNS_DEFAULT
}

# ============================================================================
# A 열 재현성 확인 — 막지 않는다
# ============================================================================
# 과거 수치를 재현하는 게이트가 아니다. 같은 창 안의 열 간 비교가 목적이므로, 어제 기록과
# 어긋나도 기록만 하고 진행한다. 어긋난다면 그것은 창 드리프트의 크기를 알려 주는 정보다.
a_recheck() {
  echo "[3shapes] === A 열 재현성 확인 (어제 summary-a.csv 대조 · 막지 않음) ==="
  local spec region cid sort_name cellspec c2 groups masks odrv omed
  for spec in "${A_RECHECK[@]}"; do
    IFS='|' read -r region cid sort_name <<<"$spec"
    odrv="$(awk -F, -v s="$sort_name" -v r="$region" -v c="$cid" \
      '$1=="A" && $2==s && $3==r && $4==c && $5=="p1" {print $6; exit}' "$ORIG_A" 2>/dev/null || true)"
    omed="$(awk -F, -v s="$sort_name" -v r="$region" -v c="$cid" \
      '$1=="A" && $2==s && $3==r && $4==c && $5=="p1" {print $13; exit}' "$ORIG_A" 2>/dev/null || true)"
    local now_drv now_med
    now_drv="$(awk -F, -v s="$sort_name" -v r="$region" -v c="$cid" \
      '$3==s && $4==r && $5==c && $8=="p1" {print $9; exit}' "$CSV_A")"
    now_med="$(awk -F, -v s="$sort_name" -v r="$region" -v c="$cid" \
      '$3==s && $4==r && $5==c && $8=="p1" {print $22; exit}' "$CSV_A")"
    local verdict="일치"
    [ "$now_drv" = "$odrv" ] || verdict="주도노드 다름"
    if [ -n "$omed" ] && [ -n "$now_med" ]; then
      awk -v m="$now_med" -v r="$omed" 'BEGIN{exit !(m>=r*0.5 && m<=r*2.0)}' || verdict="$verdict/시간 2배 밖"
    fi
    echo "[recheck] $region/$cid/$sort_name  어제 ${omed}ms($odrv)  오늘 ${now_med}ms($now_drv)  → $verdict"
    echo "[recheck] $region/$cid/$sort_name yesterday=${omed}ms/$odrv today=${now_med}ms/$now_drv verdict=$verdict" >> "$HDR"
  done
}

write_header() {
  {
    echo "================================================================"
    echo "# 세 구조 · 한 창 채취: $(date '+%Y-%m-%d %H:%M:%S %Z')"
    echo "# 컨테이너: $CONTAINER / DB: $DB / MySQL: $(q 'SELECT VERSION()')"
    echo "================================================================"
    echo
    echo "----------------------------------------------------------------"
    echo "# 설계와 해석 규칙 (사전 등록)"
    echo "#"
    echo "# ① **기반을 고정하고 쿼리 구조만 바꾼다.** 세 열 모두 통계 테이블 형태와 커버링"
    echo "#    인덱스가 V32+V33으로 같다(B만 그 위에 V34의 컬럼·인덱스를 더한다). 묻는 것이"
    echo "#    '조인 구조가 만든 문제를 각 해법이 얼마나 해결했는가'라서, 조인 구조 말고는"
    echo "#    다 같아야 하기 때문이다."
    echo "#    JOIN 열과 A 열은 **EXISTS 안의 문자열 말고는 바이트 동일한 SQL**이다."
    echo "#"
    echo "# ② **처음 구조를 V29 형상까지 되돌리지 않는다.** 그 형상에는 조인 구조 말고도"
    echo "#    테이블 2배·PK의 version·인덱스 선두의 version·비커버링 태그 인덱스가 함께 얹혀"
    echo "#    있어, 되돌리면 각 칸이 '조인을 없앤 몫'이 아니라 '그동안 쌓인 기반 개선분 전체'를"
    echo "#    재게 된다. 두 질문은 다르고 지금 묻는 것은 앞쪽이다."
    echo "#"
    echo "# ③ **세 열은 서로 비교하라고 만든 것이다.** 같은 컨테이너·같은 데이터·같은 그리드·"
    echo "#    같은 시간대·같은 술어(그룹 안 OR, 그룹 사이 AND = 확정 스펙)에서 나왔다."
    echo "#"
    echo "# ④ **원 캠페인 기록과 절대값으로 대조하지 않는다.** 2026-08-06_tag-filter-join-order와"
    echo "#    2026-08-11_tag-bitmask-read-model의 수치는 각자의 창에서 유효하고 그대로 보존한다."
    echo "#    그 창들과 이 창 사이에 실측된 드리프트가 있다 — place_tag 프로브 단가가 시간이"
    echo "#    지나며 싸졌고(단건 커버링 조회 0.622 → 0.266), 형상 전환이"
    echo "#    idx_place_stats_town_score를 재생성해 갓 만든 인덱스의 이점이 붙는다."
    echo "#    아래 [recheck] 줄이 그 드리프트의 크기를 A 열 3칸으로 보여 준다 — 판정이 아니라"
    echo "#    정보다. 어긋나도 채취를 막지 않는다."
    echo "#"
    echo "# ⑤ JOIN 열의 오추정 재현 여부는 CSV의 tags_nodes·cartesian 열로 읽는다."
    echo "#      tags_nodes > 0 — t.id가 IN 리스트라 조인이 상수로 접히지 않고 트리에 남았다"
    echo "#      cartesian  > 0 — 두 태그 축이 조건 없는 해시 조인으로 곱해졌다(추정이 무너지는 자리)"
    echo "#    단일 태그 그룹만 있는 칸은 t.id가 PK 등호라 조인이 접혀 tags_nodes=0이 정상이다."
    echo "----------------------------------------------------------------"
    echo
    echo "# 그리드: 지역 3 × 태그 7 × 정렬 2 = 42칸 + 핫콤보 커서 2페이지"
    echo "# 지역: town1=($TOWN1) town4=($TOWN4) city18=($CITY18)"
    echo "# 시간 = EXPLAIN ANALYZE 최상위 노드 actual time 끝값, 워밍업 1회 후 3회 중앙값"
    echo "#        (JOIN 열의 무거운 칸 $HEAVY_CELLS 와 핫콤보 커서는 7회)"
    echo
    echo "# JOIN·A 채취 시점 스키마 (V32+V33, 합성 태그 시드 상태)"
    qt "SELECT column_name, column_type, is_nullable FROM information_schema.columns
         WHERE table_schema='$DB' AND table_name='place_stats' ORDER BY ordinal_position;"
    qt "SELECT table_name, index_name, seq_in_index, column_name
          FROM information_schema.statistics
         WHERE table_schema='$DB' AND table_name IN ('place_stats','place_tag','places')
         ORDER BY table_name, index_name, seq_in_index;"
    qt "SELECT (SELECT COUNT(*) FROM places WHERE active=1) AS active_places,
               (SELECT COUNT(*) FROM place_stats) AS ps_rows,
               (SELECT COUNT(*) FROM place_tag) AS place_tag_rows,
               (SELECT COUNT(*) FROM tags) AS tags_rows,
               (SELECT SUM(active=0) FROM tags) AS inactive_tags;"
    qt "SELECT t.id, t.name, t.type, t.active, COUNT(pt.id) AS linked
          FROM tags t LEFT JOIN place_tag pt ON pt.tag_id=t.id
         WHERE t.id IN (1,2,5,7,8,9,10,11,12,13,14,15,16,21,40,41)
         GROUP BY t.id, t.name, t.type, t.active ORDER BY t.id;"
    qt "SELECT @@optimizer_switch AS optimizer_switch;"
  } > "$HDR"
  echo "[3shapes] 환경 기록: $HDR"
}

# ============================================================================
# 진입점
# ============================================================================
case "$MODE" in
  restore)
    if [ "$(q "SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema='$DB' AND table_name='place_stats'
                  AND column_name='tag_bitmask'" | tr -d '[:space:]')" = "0" ]; then
      SHAPE="V33"
      q "DELETE FROM place_tag WHERE tag_id IN (40,41)" >/dev/null 2>&1 || true
      q "DELETE FROM tags      WHERE id     IN (40,41)" >/dev/null 2>&1 || true
      to_v34
    else
      echo "[3shapes] 이미 현행(V34) 형상이다"
    fi
    do_verify "수동 복원" ;;
  verify) do_verify "수동" ;;
  all)
    to_v33
    seed_synth
    write_header

    CSV="$CSV_J"; csv_init "$CSV"
    run_grid "GRID" "JOIN" "join"

    CSV="$CSV_A"; csv_init "$CSV"
    run_grid "GRID" "A" "a"
    a_recheck

    to_v34
    CSV="$CSV_B"; csv_init "$CSV"
    run_grid "GRID" "B" "b"

    rollback_synth
    do_verify "최종"
    echo
    echo "[3shapes] 저장: $CSV_J / $CSV_A / $CSV_B"
    echo "[3shapes] 트리: $OUTDIR/{join,a,b}-*.txt / 환경: $HDR"
    ;;
  *) echo "사용: $0 [all|restore|verify]" >&2; exit 2 ;;
esac
