#!/usr/bin/env bash
# 계단형 라운드 하나를 1초 해상도 계측 일체와 함께 실행한다.
# 2026-08-06_minimal-stability-final/tools/run-round.sh를 계승하되, 이 캠페인의 요구인
# **구간별(계단별) 값**을 만들기 위해 1초 수집기 4개를 추가했다 — 시작/종료 누계 차분만으로는
# 계단마다의 usage·throttle·GC를 나눌 수 없다.
#
# 사용: run-step.sh <라벨> <시나리오파일> [수집초]
set -uo pipefail
cd "$(dirname "$0")/../../.."                 # -> load-test/
CAMP="2026-08-07_place-skeleton-cache"
export BENCH_CAMPAIGN="$CAMP"
LABEL="${1:?라운드 라벨 필요}"
SCEN="${2:?시나리오 파일명 필요}"
DUR="${3:-200}"                               # 수집기 수명(초). 라운드 길이 + 여유

M="campaigns/$CAMP/results/metrics"
R="campaigns/$CAMP/results/reports"
T="campaigns/$CAMP/tools"
mkdir -p "$M" "$R"

CONTAINERS=(solply-bench-app solply-bench-mysql solply-bench-lb)

# cgroup이 CPU 판정의 정본 — docker stats는 고동시성에서 상한 초과를 보고한다(함정 목록).
cgsnap() {
  for c in "${CONTAINERS[@]}"; do
    echo "== $c"
    docker exec "$c" sh -c 'echo "cpu.max: $(cat /sys/fs/cgroup/cpu.max)"; cat /sys/fs/cgroup/cpu.stat; echo "memory.current: $(cat /sys/fs/cgroup/memory.current)"; echo "memory.peak: $(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo NA)"; echo "memory.max: $(cat /sys/fs/cgroup/memory.max)"' 2>/dev/null
    echo "restart_count: $(docker inspect -f '{{.RestartCount}}' "$c" 2>/dev/null)"
    echo "nano_cpus: $(docker inspect -f '{{.HostConfig.NanoCpus}}' "$c" 2>/dev/null)"
  done
}
jvmsnap() { docker exec solply-bench-app sh -c 'wget -qO- http://localhost:8082/actuator/prometheus' 2>/dev/null; }

echo "### [$LABEL] PRE  $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
date +%s.%N > "$M/$LABEL.t_pre"
cgsnap  > "$M/$LABEL.cpustat.start"
jvmsnap > "$M/$LABEL.jvm.start"
"$T/digest-snapshot.sh" truncate "$LABEL"
./shared/metrics-snapshot.sh start "$LABEL" >/dev/null

# 1초 수집기 4개. 전부 **스스로 N회 뒤 종료**한다 — 백그라운드 kill에 의존하지 않는다
# (직전 캠페인에서 백그라운드 프로세스가 세션 하네스에 정지당해 라운드 하나를 폐기했다).
docker exec -i solply-bench-app   sh -s "$DUR" < "$T/collect-app.sh" > "$M/$LABEL.app1s.tsv"    2>"$M/$LABEL.app1s.err"    &
P_APP=$!
docker exec -i solply-bench-app   sh -s "$DUR" < "$T/collect-jvm.sh" > "$M/$LABEL.jvm1s.tsv"    2>"$M/$LABEL.jvm1s.err"    &
P_JVM=$!
docker exec -i solply-bench-mysql sh -s "$DUR" < "$T/collect-db.sh"  > "$M/$LABEL.db1s.tsv"     2>"$M/$LABEL.db1s.err"     &
P_DB=$!
"$T/collect-mysql-status.sh" "$DUR" > "$M/$LABEL.dbstat1s.tsv" 2>"$M/$LABEL.dbstat1s.err" &
P_ST=$!

sleep 3                                        # 수집기가 첫 샘플을 찍고 나서 부하를 건다
date +%s.%N > "$M/$LABEL.t_artillery_start"
npx artillery run "campaigns/$CAMP/scenarios/$SCEN" -o "$R/$LABEL.json" >"$M/$LABEL.artillery.log" 2>&1
date +%s.%N > "$M/$LABEL.t_artillery_end"
echo "### [$LABEL] POST $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"

cgsnap  > "$M/$LABEL.cpustat.end"
jvmsnap > "$M/$LABEL.jvm.end"
"$T/digest-snapshot.sh" dump "$LABEL"
./shared/metrics-snapshot.sh report "$LABEL" >/dev/null

# 수집기가 남은 초를 마저 채우고 스스로 끝나기를 기다린다 (라운드 뒤 회복 구간도 남는다).
wait "$P_APP" "$P_JVM" "$P_DB" "$P_ST" 2>/dev/null
echo "### [$LABEL] DONE $(date '+%H:%M:%S')  샘플 app=$(( $(wc -l < "$M/$LABEL.app1s.tsv") - 1 )) db=$(( $(wc -l < "$M/$LABEL.db1s.tsv") - 1 )) jvm=$(( $(wc -l < "$M/$LABEL.jvm1s.tsv") - 1 )) dbstat=$(( $(wc -l < "$M/$LABEL.dbstat1s.tsv") - 1 ))"
