#!/usr/bin/env bash
# 라운드 C 전용 — 배치 정합성 증거 채취. 부하와 무관하게 DB 상태만 읽는다.
#
# 사용: batch-watch.sh before <라벨>   (라운드 시작 직전)
#       batch-watch.sh after  <라벨>   (라운드 종료 직후 — 배치 로그·버전 상태 diff)
set -euo pipefail
cd "$(dirname "$0")/.."                      # -> campaigns/<캠페인>/
PHASE="${1:?before|after}"
LABEL="${2:?라벨 필요}"
OUT="results/metrics/$LABEL.batch.$PHASE"
mkdir -p results/metrics

q() { docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd \
        --default-character-set=utf8mb4 -t solply_bench_db -e "$1" 2>/dev/null; }

{
  echo "# $PHASE  $(date '+%Y-%m-%d %H:%M:%S %Z')"
  q "SELECT * FROM place_stats_meta;"
  q "SELECT version, COUNT(*) AS rows_in_version FROM place_stats GROUP BY version ORDER BY version;"
  q "SELECT COUNT(DISTINCT version) AS distinct_versions, COUNT(*) AS total_rows FROM place_stats;"
  q "SELECT name, lock_until, locked_at, locked_by FROM shedlock;"
  echo "--- 앱 로그의 배치 줄 (최근 20) ---"
  # 아직 한 번도 안 돈 시점에는 매치가 0건이다 — set -e에 걸리지 않게 || true를 붙인다.
  docker logs solply-bench-app 2>&1 | grep -E "인기순 점수" | tail -20 || true
} > "$OUT"
cat "$OUT"
echo "[batch-watch] 저장: $OUT"
