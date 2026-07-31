#!/usr/bin/env bash
# 배치 실행 중 MySQL 컨테이너의 CPU·메모리를 1초 간격으로 샘플링한다.
#
# 왜 shared/sampler.sh를 안 쓰는가:
#   그 스크립트는 앱 2대 + LB를 프리플라이트로 요구한다(없으면 exit 1). 이 캠페인은 DB 레벨만
#   측정하므로 앱을 띄우지 않는다. 공용 도구를 고치면 다른 캠페인의 계약이 바뀌므로
#   캠페인 로컬 도구를 둔다.
#
# 간격도 3초가 아니라 1초다 — 배치가 6초대라 3초 간격이면 표본이 2개뿐이다.
#
# 사용: ./sample-mysql.sh <라벨> [지속초=60] &
set -uo pipefail
cd "$(dirname "$0")"
LABEL="${1:?라벨 필요}"
DURATION="${2:-60}"
OUT="../results/metrics/$LABEL.mysql-samples.csv"
mkdir -p "$(dirname "$OUT")"

echo "ts,cpu_pct,mem_used,mem_pct" > "$OUT"
END=$((SECONDS + DURATION))
while [ $SECONDS -lt $END ]; do
  LINE=$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' solply-bench-mysql 2>/dev/null || echo "NA,NA,NA")
  echo "$(date +%H:%M:%S),$LINE" >> "$OUT"
done
