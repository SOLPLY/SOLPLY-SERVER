#!/usr/bin/env bash
# 항목 2 — "캐시 미스마다 실시간 집계"를 왜 버렸는가.
#
# v0(2026-07-29 캠페인에서 부하로 기각됨, 성공률 1.6%)의 실제 경로는
# BookmarkRepository.countByPlaceIds — 시 단위 후보 전체를 IN으로 넘겨 GROUP BY 하는 한 방이다.
# 그 기각의 **원인 지표**(읽는 행 수)를 남기는 것이 이 스크립트의 목적이다. 부하는 다시 걸지 않는다.
#
# 리터럴 IN 리스트를 만들어 넘기는 이유: 앱이 실제로 그렇게 보낸다.
# 서브쿼리(`IN (SELECT ...)`)로 바꾸면 옵티마이저가 세미조인으로 다시 쓰기 때문에
# 프로덕션 경로와 다른 계획을 재게 된다.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../results/explain/realtime-count.txt

# 서울 = root 201 / leaf 301~318. 시 단위 정렬 후보 상한 1,800곳 (설계 §1.2).
#
# ⚠️ GROUP_CONCAT으로 만들지 않는다 — group_concat_max_len 기본 1024바이트에 **조용히 잘린다.**
# 처음에 그렇게 짰다가 1,800곳이 171곳으로 줄고 마지막 항목이 `1017`이라는 잘린 쓰레기 id로
# 들어간 채 EXPLAIN이 정상 출력됐다. 결과가 그럴듯해서 행 수를 세 보기 전엔 눈치채지 못한다.
# 행을 그대로 받아 셸에서 잇는 편이 상한이 없고 검증도 쉽다.
IDS=$(./mysql.sh -N -e "SELECT id FROM places
      WHERE town_id BETWEEN 301 AND 318 AND active = true ORDER BY id;" | tr '\n' ',' | sed 's/,$//')
N=$(echo "$IDS" | tr ',' '\n' | wc -l | tr -d ' ')
echo "[explain] 후보 장소 ${N}곳"
[ "$N" -eq 1800 ] || { echo "[explain] 1,800곳이 아니다 — 시드나 쿼리를 확인할 것" >&2; exit 1; }

{
  echo "SELECT '=== A. 실시간 COUNT — 단일 장소 (1위, 북마크 69,784건) ===' AS \`\`;"
  echo "EXPLAIN ANALYZE SELECT COUNT(*) FROM bookmarks WHERE target_type='PLACE' AND target_id=10001;"

  echo "SELECT '=== B. 실시간 COUNT — v0 실제 경로 countByPlaceIds (서울 1,800곳) ===' AS \`\`;"
  echo "EXPLAIN ANALYZE SELECT b.target_id, COUNT(*) AS cnt FROM bookmarks b"
  echo "  WHERE b.target_type='PLACE' AND b.target_id IN ($IDS) GROUP BY b.target_id;"

  echo "SELECT '=== C. 현행 경로 — place_stats PK IN 조회 (같은 1,800곳) ===' AS \`\`;"
  echo "EXPLAIN ANALYZE SELECT ps.place_id, ps.popular_score, ps.bookmark_count, ps.calculated_at"
  echo "  FROM place_stats ps WHERE ps.place_id IN ($IDS);"

  echo "SELECT '=== D. 전국 규모였다면 (6,000곳 전체) — 상한 감각용 ===' AS \`\`;"
  echo "EXPLAIN ANALYZE SELECT b.target_id, COUNT(*) FROM bookmarks b"
  echo "  WHERE b.target_type='PLACE' GROUP BY b.target_id;"
} | ./mysql.sh -t > "$OUT" 2>&1

# EXPLAIN ANALYZE는 계획과 실측을 한 덩어리로 뱉는다. 읽기 편하게 핵심만 추린다.
sed -e 's/^| //' -e 's/ *|$//' "$OUT" | grep -vE '^\+|^\| EXPLAIN|^$'
echo
echo "[explain] 전문: $OUT"
