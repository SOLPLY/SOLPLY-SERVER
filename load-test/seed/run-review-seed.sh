#!/usr/bin/env bash
# 리뷰 20만 건을 기존 벤치 시드에 추가한다. 멱등 — 벤치 리뷰를 지우고 다시 넣는다.
#
# 선행: 2026-07-30_place-popular-baseline/seed/run-bench-seed.sh 가 이미 돌아
#       유저 10만 / 장소 6,000 / 북마크 1,039만이 있어야 한다. 이 스크립트는 그것을 만들지 않는다.
#
# 장소 분포를 DB에서 뽑아 넘기는 이유는 generate-review-seed.mjs 상단 주석 참조 —
# 요약하면 리뷰 축이 북마크 축과 같은 인기 분포를 따라야 감쇠 점수 검증이 성립한다.
set -euo pipefail
cd "$(dirname "$0")"
MYSQL=../bench/mysql.sh
DIST=$(mktemp -t place-dist)
trap 'rm -f "$DIST"' EXIT

echo "[seed] 장소 분포 추출 $(date +%T)"
# -N: 헤더 없이. r7은 트렌딩 판정용(최근 7일 북마크 수).
$MYSQL -N -e "
SELECT target_id, COUNT(*), SUM(created_at >= NOW() - INTERVAL 7 DAY)
FROM bookmarks WHERE target_type='PLACE' AND target_id >= 10001
GROUP BY target_id;" > "$DIST"
echo "[seed] 장소 $(wc -l < "$DIST" | tr -d ' ')곳"

echo "[seed] 리뷰 생성·삽입 시작 $(date +%T)"
node generate-review-seed.mjs "$DIST" | $MYSQL
echo "[seed] 완료 $(date +%T)"
