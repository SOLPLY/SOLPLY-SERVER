#!/usr/bin/env bash
# 한 라운드를 계측 일체와 함께 실행한다 — amp 캠페인 run-round.sh 계승 (EXPLAIN 채취만 제거:
# 실행계획은 post-fix 검증으로 확정됐고 이 캠페인의 판정 지표가 아니다).
#
# 사용: run-round.sh <라운드라벨> <시나리오파일>
#   예: run-round.sh recheck300-r1 place-list-popular-readonly-300.yml
set -uo pipefail
cd "$(dirname "$0")/../../.."                 # -> load-test/
CAMP="2026-08-05_improved-recheck"
export BENCH_CAMPAIGN="$CAMP"
LABEL="${1:?라운드 라벨 필요}"
SCEN="${2:?시나리오 파일명 필요}"

M="campaigns/$CAMP/results/metrics"
R="campaigns/$CAMP/results/reports"
T="campaigns/$CAMP/tools"
CPUSH=/private/tmp/claude-501/-Users-mkyu-Desktop-SOPT-solply-server/7b79ed77-0424-448d-9e57-8f5dd9e975c5/scratchpad/artillery-cpu.sh
mkdir -p "$M" "$R"

# cgroup cpu.stat이 CPU 판정의 정본 — docker stats는 고동시성에서 상한 초과 보고 (함정 목록)
cgsnap() { for c in solply-bench-app-1 solply-bench-app-2 solply-bench-mysql solply-bench-lb; do
  echo "== $c"; docker exec "$c" sh -c 'cat /sys/fs/cgroup/cpu.stat' 2>/dev/null; done; }

echo "### [$LABEL] PRE  $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
cgsnap > "$M/$LABEL.cpustat.start"
"$T/digest-snapshot.sh" truncate "$LABEL"
./shared/metrics-snapshot.sh start "$LABEL" >/dev/null
./shared/sampler.sh "$LABEL" 200 &
[ -x "$CPUSH" ] && $CPUSH "$M/$LABEL.artillery-cpu" 200 3 &

npx artillery run "campaigns/$CAMP/scenarios/$SCEN" -o "$R/$LABEL.json" >/dev/null 2>&1
echo "### [$LABEL] POST $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
cgsnap > "$M/$LABEL.cpustat.end"
"$T/digest-snapshot.sh" dump "$LABEL"
wait
./shared/metrics-snapshot.sh report "$LABEL" >/dev/null
echo "### [$LABEL] DONE $(date '+%H:%M:%S')"
