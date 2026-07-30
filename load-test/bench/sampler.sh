#!/usr/bin/env bash
# 부하 라운드 동안 약 3초 간격으로 컨테이너 CPU/메모리 + MySQL 커넥션 수를 CSV 샘플링.
# 사용: ./sampler.sh <라벨> [지속초=200] &   (백그라운드로 띄우고 Artillery 실행)
# 앱 2대 + LB 구성 대응 (2026-07-30).
set -euo pipefail
cd "$(dirname "$0")"
LABEL="${1:?라벨 필요}"
DURATION="${2:-200}"
OUT="results/metrics/$LABEL.samples.csv"
mkdir -p results/metrics

echo "ts,app1_cpu,app1_mem,app2_cpu,app2_mem,lb_cpu,mysql_cpu,mysql_mem,threads_connected,threads_running" > "$OUT"
END=$((SECONDS + DURATION))
while [ "$SECONDS" -lt "$END" ]; do
  STATS=$(docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
    solply-bench-app-1 solply-bench-app-2 solply-bench-lb solply-bench-mysql)
  A1=$(echo "$STATS" | grep 'bench-app-1' | cut -d, -f2,3)
  A2=$(echo "$STATS" | grep 'bench-app-2' | cut -d, -f2,3)
  LB=$(echo "$STATS" | grep 'bench-lb'    | cut -d, -f2)
  DB=$(echo "$STATS" | grep 'bench-mysql' | cut -d, -f2,3)
  TC=$(docker exec solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'" 2>/dev/null | awk '{print $2}')
  TR=$(docker exec solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N -e "SHOW GLOBAL STATUS LIKE 'Threads_running'" 2>/dev/null | awk '{print $2}')
  echo "$(date +%T),$A1,$A2,$LB,$DB,$TC,$TR" >> "$OUT"
  sleep 3
done
echo "[sampler] 저장: $OUT"
