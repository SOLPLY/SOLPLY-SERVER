#!/usr/bin/env bash
# 고정 파라미터 EXPLAIN 세트 — 등록서 조건 ②(플랜이 range → ALL로 뒤집히는가) 전용.
#
# 규율: 문장과 값이 시점마다 **완전히 동일**해야 한다. 값이 흔들리면 플랜 차이가 부하 때문인지
# 파라미터 때문인지 갈라낼 수 없다. 그래서 커서 값도 실측으로 한 번 뽑아 여기 하드코딩했다:
#   시 단위(leaf 301~318) 인기순 1페이지의 20번째 행 = place_id 10503, popular_score 11662.295015
#   (채취 2026-08-04, 시드 불변이므로 캠페인 내내 유효)
#
# SQL 본문은 PlaceListDbQueryRepository.findPopularRows가 만드는 문자열을 그대로 옮긴 것이다 —
# 컬럼 순서·JOIN 조건·ORDER BY·LIMIT 21(size 20 + 1)까지 동일해야 같은 digest에 붙는다.
# 앱과 같은 계정(solplyuser)으로 접속해 권한·세션 기본값 차이를 줄인다.
#
# 사용: explain-set.sh <시점라벨>   (예: pre-load, during-300, during-500A, post-500A, pre-500B)
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/<캠페인>/
LABEL="${1:?시점 라벨 필요}"
ANALYZE="${2:-no}"                           # yes면 시 단위 쿼리 1개만 EXPLAIN ANALYZE 추가
OUT_DIR="results/explain"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$LABEL.txt"

CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
CUR_SCORE="11662.295015"
CUR_ID="10503"

# --- 고정 문장 4개 -------------------------------------------------------------
Q_CITY="SELECT ps.place_id, ps.popular_score, ps.bookmark_count
FROM place_stats ps
JOIN places p ON p.id = ps.place_id AND p.active = 1
WHERE ps.town_id IN ($CITY)
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 21"

Q_CITY_CURSOR="SELECT ps.place_id, ps.popular_score, ps.bookmark_count
FROM place_stats ps
JOIN places p ON p.id = ps.place_id AND p.active = 1
WHERE ps.town_id IN ($CITY)
  AND (ps.popular_score < $CUR_SCORE
       OR (ps.popular_score = $CUR_SCORE AND ps.place_id > $CUR_ID))
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 21"

Q_TOWN="SELECT ps.place_id, ps.popular_score, ps.bookmark_count
FROM place_stats ps
JOIN places p ON p.id = ps.place_id AND p.active = 1
WHERE ps.town_id IN (301)
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 21"

Q_TAG="SELECT ps.place_id, ps.popular_score, ps.bookmark_count
FROM place_stats ps
JOIN places p ON p.id = ps.place_id AND p.active = 1
WHERE ps.town_id IN (305)
  AND EXISTS (SELECT 1 FROM place_tag pt JOIN tags t ON t.id = pt.tag_id
               WHERE pt.place_id = p.id AND t.id = 3 AND t.active = 1)
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 21"

run() {   # run <이름> <SQL>
  local name="$1" sql="$2"
  {
    echo "################################################################"
    echo "# [$LABEL] $name   ($(date '+%Y-%m-%d %H:%M:%S'))"
    echo "################################################################"
    echo "--- EXPLAIN (traditional) ---"
    docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -t solply_bench_db \
      -e "EXPLAIN $sql;" 2>/dev/null
    echo "--- EXPLAIN FORMAT=TREE ---"
    docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N solply_bench_db \
      -e "EXPLAIN FORMAT=TREE $sql;" 2>/dev/null
    echo
  } >> "$OUT"
}

echo "==================== 시점: $LABEL / $(date '+%H:%M:%S') ====================" >> "$OUT"
run "city-nocursor (leaf 18개, 커서 없음)"      "$Q_CITY"
run "city-cursor   (leaf 18개, 대표 커서)"      "$Q_CITY_CURSOR"
run "town-nocursor (동네 1개=301)"              "$Q_TOWN"
run "tag-filter    (동네 305 + mainTag 3)"      "$Q_TAG"

if [ "$ANALYZE" = "yes" ]; then
  # 등록서: 포화 중 EXPLAIN ANALYZE는 시 단위 1개만 (실제 실행이라 부하를 더하므로 최소화)
  {
    echo "################################################################"
    echo "# [$LABEL] EXPLAIN ANALYZE — city-nocursor 만 ($(date '+%H:%M:%S'))"
    echo "################################################################"
    docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N solply_bench_db \
      -e "EXPLAIN ANALYZE $Q_CITY;" 2>/dev/null
    echo
  } >> "$OUT"
fi

echo "[explain] 저장: $OUT"
