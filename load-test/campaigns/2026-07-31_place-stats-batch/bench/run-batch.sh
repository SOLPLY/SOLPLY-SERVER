#!/usr/bin/env bash
# 항목 1 — 배치 소요 + 멱등성.
#
# 4회 실행하고 **첫 회는 버린다** (버퍼풀 워밍업). 유효 3회의 중앙값으로 판정한다.
# 4회 모두 같은 calculatedAt을 쓰므로, 2~4회차의 지문이 서로 같으면 그것이 곧 멱등성 증거다.
# (1회차는 INSERT 경로, 2회차부터는 UPDATE 경로라 지문이 같다는 것은 두 경로가 같은 값을
#  만든다는 뜻이기도 하다 — ON DUPLICATE KEY UPDATE가 일부 컬럼을 빠뜨렸다면 여기서 갈린다)
#
# 사용: ./run-batch.sh [라벨접두=batch]
set -euo pipefail
cd "$(dirname "$0")"
PREFIX="${1:-batch}"
ROUNDS=4
CAT='2026-07-31 02:00:00'   # 고정 기준 시각. 회차 간 비교가 성립하려면 같아야 한다.
export BENCH_CAMPAIGN=2026-07-31_place-stats-batch
SNAP=../../../shared/metrics-snapshot.sh

echo "=== 배치 측정 시작 $(date +%T) / calculatedAt=$CAT ==="
uptime

for i in $(seq 1 $ROUNDS); do
  LABEL="$PREFIX-r$i"
  echo
  echo "--- 라운드 $i/$ROUNDS ($LABEL) $(date +%T) ---"

  $SNAP start "$LABEL" > /dev/null
  ./sample-mysql.sh "$LABEL" 40 &
  SAMPLER=$!

  # @cat을 세션 변수로 먼저 세팅한 뒤 라운드 SQL을 이어 붙인다.
  # 한 mysql 프로세스 = 한 세션이어야 @cat과 트랜잭션이 이어진다.
  { echo "SET @cat = '$CAT';"; cat batch-round.sql; } | ./mysql.sh -t \
    | tee "../results/metrics/$LABEL.batch.txt"

  kill $SAMPLER 2>/dev/null || true
  wait $SAMPLER 2>/dev/null || true
  $SNAP report "$LABEL" > "../results/metrics/$LABEL.diff.txt"
  cat "../results/metrics/$LABEL.diff.txt"

  [ $i -lt $ROUNDS ] && { echo "[rest] 10초"; sleep 10; }
done

echo
echo "=== 요약 ==="
grep -h -A 2 batch_ms ../results/metrics/$PREFIX-r*.batch.txt | grep -E '^\|[[:space:]]*[0-9]' || true
echo "--- 지문 (2회차부터 모두 같아야 멱등) ---"
grep -h -A 4 'fingerprint' ../results/metrics/$PREFIX-r*.batch.txt || true
