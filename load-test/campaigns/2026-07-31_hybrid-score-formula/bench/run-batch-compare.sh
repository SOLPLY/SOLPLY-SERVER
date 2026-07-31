#!/usr/bin/env bash
# 성공 조건 3 — ln 항 추가가 배치 소요를 늘리는가.
#
# 각 변형을 4회 돌리고 **첫 회는 버린다** (버퍼풀 워밍업). 유효 3회의 중앙값으로 판정한다.
# 기존 실측 6,049ms는 2026-07-31_place-stats-batch가 같은 방식(4회−1)으로 얻은 값이라
# 회차 수를 맞춰야 비교가 성립한다.
#
# calculatedAt은 두 변형 모두 같은 고정 시각을 쓴다. NOW()를 쓰면 회차마다 값이 달라져
# "같은 입력에 대한 소요 비교"라는 전제가 깨진다.
#
# sampler·metrics-snapshot을 붙이지 않는다: 이 측정의 주장은 "벽시계 소요"뿐이고
# (부하 테스트가 아니라 결정적 SQL 비교) 계측 자체가 DB 컨테이너 CPU를 먹는다.
#
# ⚠️ hybrid 변형은 place_stats.popular_score에 ln 항이 섞인 값을 쓴다.
#    마지막에 현행 배치를 한 번 더 돌려 **원상 복구**한다 — 벤치 DB는 다음 캠페인에 물려주는 상태다.
set -euo pipefail
cd "$(dirname "$0")"

CAT='2026-07-31 02:00:00'   # place_stats의 현재 세대와 같은 기준 시각 = 복구가 성립하는 값
ALPHA=100                   # 후보 중간값. ln 항의 유무가 논점이지 α 크기는 소요와 무관하다
MYSQL=../../2026-07-31_place-stats-batch/bench/mysql.sh
OUT=../results/metrics

run_round () {   # $1=라벨  $2=SQL파일
  { echo "SET @cat = '$CAT'; SET @alpha = $ALPHA;"; cat "$2"; } \
    | $MYSQL -t | tee "$OUT/$1.txt"
}

echo "=== 배치 소요 비교 $(date +%T) / calculatedAt=$CAT / alpha=$ALPHA ==="
uptime

for i in 0 1 2 3; do
  echo "--- current r$i ---"
  run_round "batch-current-r$i" ../../2026-07-31_place-stats-batch/bench/batch-round.sql
  sleep 5
done

for i in 0 1 2 3; do
  echo "--- hybrid r$i ---"
  run_round "batch-hybrid-r$i" batch-round-hybrid.sql
  sleep 5
done

echo "--- 복구: 현행 공식으로 place_stats 되돌리기 ---"
run_round "batch-restore" ../../2026-07-31_place-stats-batch/bench/batch-round.sql

echo
echo "=== 요약 (r0은 워밍업이므로 판정에서 제외) ==="
for f in $OUT/batch-*.txt; do
  printf '%-24s %s\n' "$(basename "$f" .txt)" "$(grep -Eo '[0-9]+\.[0-9]+' "$f" | head -1)"
done
