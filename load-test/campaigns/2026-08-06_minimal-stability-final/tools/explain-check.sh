#!/usr/bin/env bash
# 부하 라운드에서 실제로 나가는 태그 조합 SQL의 실행계획을 채취한다 — digest의 뒷받침용.
# digest는 "tags를 조인하지 않는다"까지만 보여주고 **주도 테이블이 무엇인가**는 안 보여준다.
#
# 부하 중에 돌리지 않는다 (계측이 부하가 된다). 라운드 전/후 무부하 시점에만 쓴다.
# 사용: explain-check.sh <라벨>
set -euo pipefail
cd "$(dirname "$0")/.."
LABEL="${1:?라벨 필요}"
OUT="results/explain/$LABEL.txt"
mkdir -p results/explain
q() { docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -N --raw solply_bench_db -e "$1" 2>/dev/null; }

CITY="301,302,303,304,305,306,307,308,309,310,311,312,313,314,315,316,317,318"
V=$(q "SELECT current_generation FROM place_stats_meta WHERE id = 1" | tr -d '[:space:]')

POP="SELECT ps.place_id, ps.popular_score, ps.bookmark_count, ps.review_count, ps.avg_rating
FROM place_stats ps
WHERE ps.version = $V AND ps.town_id IN ($CITY)
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = ps.place_id AND pt.tag_id = 1)
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = ps.place_id AND pt.tag_id = 7)
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = ps.place_id AND pt.tag_id = 12)
ORDER BY ps.popular_score DESC, ps.place_id ASC LIMIT 21"

LAT="SELECT p.id, p.created_at, COALESCE(ps.bookmark_count, 0), COALESCE(ps.review_count, 0), ps.avg_rating
FROM places p
LEFT JOIN place_stats ps ON ps.place_id = p.id
     AND ps.version = (SELECT current_generation FROM place_stats_meta WHERE id = 1)
WHERE p.town_id IN ($CITY) AND p.active = 1
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.tag_id = 1)
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.tag_id = 7)
  AND EXISTS (SELECT 1 FROM place_tag pt WHERE pt.place_id = p.id AND pt.tag_id = 12)
ORDER BY p.created_at DESC, p.id DESC LIMIT 21"

{
  echo "# 라벨: $LABEL / 채취 $(date '+%Y-%m-%d %H:%M:%S %Z') / version = $V"
  echo "# 시 단위(서울 leaf 18곳) + 메인1 + 서브A 7 + 서브B 12 — 부하 시나리오의 태그 경로와 같은 모양"
  for nm in 인기순 최신순; do
    sql="$POP"; [ "$nm" = "최신순" ] && sql="$LAT"
    echo "################ $nm ################"
    echo "--- SQL ---"; echo "$sql"
    q "$sql" > /dev/null   # 워밍업
    echo "--- EXPLAIN ANALYZE ---"; q "EXPLAIN ANALYZE $sql"
    echo
  done
} > "$OUT"
echo "[explain] 저장: $OUT"
