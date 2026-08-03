#!/usr/bin/env bash
# 부하 라운드 동안 약 3초 간격으로 컨테이너 CPU/메모리 + MySQL 커넥션 수를 CSV 샘플링.
# 사용: ./sampler.sh <라벨> [지속초=200] &   (백그라운드로 띄우고 Artillery 실행)
# 앱 2대 + LB 구성 대응 (2026-07-30).
set -uo pipefail
# 캠페인 디렉터리에 기록한다 — BENCH_CAMPAIGN 없이 돌면 산출물이 캠페인 밖에 떨어지므로 즉시 실패.
cd "$(dirname "$0")/.."               # -> load-test/
LABEL="${1:?라벨 필요}"
DURATION="${2:-200}"
OUT="campaigns/${BENCH_CAMPAIGN:?BENCH_CAMPAIGN 환경변수 필요 (캠페인 디렉터리명)}/results/metrics/$LABEL.samples.csv"
CONTAINERS=(solply-bench-app-1 solply-bench-app-2 solply-bench-lb solply-bench-mysql)
mkdir -p "$(dirname "$OUT")"

# 프리플라이트: 대상 컨테이너가 하나라도 안 떠 있으면 즉시 실패한다.
# 부하 도중 샘플러가 조용히 죽어 리소스 데이터를 통째로 잃는 것보다,
# 시작 전에 원인을 드러내고 멈추는 편이 낫다.
MISSING=()
for c in "${CONTAINERS[@]}"; do
  [ "$(docker inspect -f '{{.State.Running}}' "$c" 2>/dev/null)" = "true" ] || MISSING+=("$c")
done
if [ ${#MISSING[@]} -gt 0 ]; then
  echo "[sampler] 실행 중이 아닌 컨테이너: ${MISSING[*]}" >&2
  echo "[sampler] docker compose -f docker/docker-compose.bench.yml up -d 로 먼저 기동하세요." >&2
  exit 1
fi

# 루프 안에서는 fail-fast를 끈다 — 컨테이너가 순간적으로 재시작해도 샘플링을 계속하고
# 해당 칸만 NA로 남긴다. 측정 전체를 잃지 않기 위함이다.
field() { echo "$1" | grep "$2" | cut -d, -f"$3" || echo "NA"; }

echo "ts,app1_cpu,app1_mem,app2_cpu,app2_mem,lb_cpu,mysql_cpu,mysql_mem,threads_connected,threads_running" > "$OUT"
END=$((SECONDS + DURATION))
while [ "$SECONDS" -lt "$END" ]; do
  STATS=$(docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' "${CONTAINERS[@]}" 2>/dev/null || echo "")
  A1=$(field "$STATS" 'bench-app-1' 2,3)
  A2=$(field "$STATS" 'bench-app-2' 2,3)
  LB=$(field "$STATS" 'bench-lb'    2)
  DB=$(field "$STATS" 'bench-mysql' 2,3)
  TC=$(docker exec solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N -e "SHOW GLOBAL STATUS LIKE 'Threads_connected'" 2>/dev/null | awk '{print $2}')
  TR=$(docker exec solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N -e "SHOW GLOBAL STATUS LIKE 'Threads_running'" 2>/dev/null | awk '{print $2}')
  echo "$(date +%T),$A1,$A2,$LB,${DB},${TC:-NA},${TR:-NA}" >> "$OUT"
  sleep 3
done
echo "[sampler] 저장: $OUT"
