#!/usr/bin/env bash
# 한 라운드를 계측 일체와 함께 실행한다. 2026-08-05 캠페인의 run-round.sh를 계승하되
# ① 컨테이너 이름을 현행 단일 앱(solply-bench-app)으로 고치고 ② JVM 지표(actuator/prometheus)
# 스냅샷을 추가했다. 옛 스크립트는 solply-bench-app-1/-2를 참조해 지금은 빈 파일을 남긴다.
#
# 사용: run-round.sh <라운드라벨> <시나리오파일>
#   예: run-round.sh r-a-120 stability-120.yml
set -uo pipefail
cd "$(dirname "$0")/../../.."                 # -> load-test/
CAMP="2026-08-06_minimal-stability-final"
export BENCH_CAMPAIGN="$CAMP"
LABEL="${1:?라운드 라벨 필요}"
SCEN="${2:?시나리오 파일명 필요}"
SAMPLE_SECS="${3:-140}"                       # 라운드 100초 + 여유

M="campaigns/$CAMP/results/metrics"
R="campaigns/$CAMP/results/reports"
T="campaigns/$CAMP/tools"
mkdir -p "$M" "$R"

CONTAINERS=(solply-bench-app solply-bench-mysql solply-bench-lb)

# cgroup cpu.stat이 CPU 판정의 정본 — docker stats는 고동시성에서 상한 초과 보고 (함정 목록).
# memory.peak도 같은 파일 트리에서 읽는다 (컨테이너 메모리 peak = 사전 등록 S5의 근거).
cgsnap() {
  for c in "${CONTAINERS[@]}"; do
    echo "== $c"
    docker exec "$c" sh -c 'echo "cpu.max: $(cat /sys/fs/cgroup/cpu.max)"; cat /sys/fs/cgroup/cpu.stat; echo "memory.current: $(cat /sys/fs/cgroup/memory.current)"; echo "memory.peak: $(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo NA)"; echo "memory.max: $(cat /sys/fs/cgroup/memory.max)"' 2>/dev/null
    echo "restart_count: $(docker inspect -f '{{.RestartCount}}' "$c" 2>/dev/null)"
  done
}
# JVM 공식 지표 — nginx는 /actuator를 403으로 막으므로 컨테이너 안에서 읽는다.
jvmsnap() { docker exec solply-bench-app sh -c 'wget -qO- http://localhost:8082/actuator/prometheus' 2>/dev/null; }

echo "### [$LABEL] PRE  $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
cgsnap  > "$M/$LABEL.cpustat.start"
jvmsnap > "$M/$LABEL.jvm.start"
"$T/digest-snapshot.sh" truncate "$LABEL"
./shared/metrics-snapshot.sh start "$LABEL" >/dev/null
./shared/sampler.sh "$LABEL" "$SAMPLE_SECS" &
SAMPLER_PID=$!

# 라운드 C 전용 정렬 가드 (기본 off — A·B 라운드는 이 블록을 통과하지 않는다).
# 배치 cron 발화 시각을 정상 구간(t+40~t+100) 안에 넣으려면 artillery의 첫 요청 시각을
# 발화 경계로부터 역산해 맞춰야 한다. PRE 스냅샷이 몇 초를 먹으므로 스크립트 시작이 아니라
# **artillery 직전**에 정렬한다. 백그라운드 예약을 쓰지 않는 이유는 #C 첫 시도의 폐기 원인
# (대기 프로세스가 세션 하네스에 정지당해 라운드가 15분 늦게 시작)이다 — 포그라운드로만 센다.
if [ -n "${BENCH_ALIGN_PERIOD:-}" ]; then
  LO="${BENCH_ALIGN_LO:?정렬 하한 필요}"; HI="${BENCH_ALIGN_HI:?정렬 상한 필요}"
  echo "### [$LABEL] ALIGN 대기 시작 $(date '+%H:%M:%S') (경계까지 ${LO}~${HI}s 구간을 기다린다)"
  while :; do
    TO_EDGE=$(( BENCH_ALIGN_PERIOD - ( $(date +%s) % BENCH_ALIGN_PERIOD ) ))
    [ "$TO_EDGE" -ge "$LO" ] && [ "$TO_EDGE" -le "$HI" ] && break
    sleep 0.2
  done
  echo "### [$LABEL] ALIGN 완료 $(date '+%H:%M:%S')  다음 배치 경계까지 ${TO_EDGE}s"
fi

npx artillery run "campaigns/$CAMP/scenarios/$SCEN" -o "$R/$LABEL.json" >"$M/$LABEL.artillery.log" 2>&1
echo "### [$LABEL] POST $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"

cgsnap  > "$M/$LABEL.cpustat.end"
jvmsnap > "$M/$LABEL.jvm.end"
"$T/digest-snapshot.sh" dump "$LABEL"
./shared/metrics-snapshot.sh report "$LABEL" >/dev/null
kill "$SAMPLER_PID" 2>/dev/null
wait "$SAMPLER_PID" 2>/dev/null
echo "### [$LABEL] DONE $(date '+%H:%M:%S')"
