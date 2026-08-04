#!/usr/bin/env bash
# 한 라운드를 계측 일체와 함께 실행한다. 계측이 6라운드에 걸쳐 동일해야 A/B 비교가 성립하므로
# 순서를 스크립트로 굳혔다 (손으로 반복하면 한 라운드에서 한 단계를 빠뜨리기 쉽다).
#
# 사용: run-round.sh <라운드라벨> <시나리오파일> [EXPLAIN라벨] [EXPLAIN지연초] [analyze:yes|no]
#   예: run-round.sh amp300-r1 place-list-popular-readonly-300.yml during-300 100 no
set -uo pipefail
cd "$(dirname "$0")/../../.."                 # -> load-test/
CAMP="2026-08-04_saturation-amplification"
export BENCH_CAMPAIGN="$CAMP"
LABEL="${1:?라운드 라벨 필요}"
SCEN="${2:?시나리오 파일명 필요}"
EXP_LABEL="${3:-}"
EXP_DELAY="${4:-100}"
EXP_ANALYZE="${5:-no}"

M="campaigns/$CAMP/results/metrics"
R="campaigns/$CAMP/results/reports"
T="campaigns/$CAMP/tools"
CPUSH=/private/tmp/claude-501/-Users-mkyu-Desktop-SOPT-solply-server/7b79ed77-0424-448d-9e57-8f5dd9e975c5/scratchpad/artillery-cpu.sh
mkdir -p "$M" "$R"

# cgroup cpu.stat이 CPU 판정의 정본이다 — docker stats는 고동시성에서 배정 상한을 초과 보고하는
# 오차가 확인돼 함정 목록에 등재됐다 (등록서 §5). sampler CSV는 추이 참고용으로만 남긴다.
cgsnap() { for c in solply-bench-app-1 solply-bench-app-2 solply-bench-mysql solply-bench-lb; do
  echo "== $c"; docker exec "$c" sh -c 'cat /sys/fs/cgroup/cpu.stat' 2>/dev/null; done; }

echo "### [$LABEL] PRE  $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
cgsnap > "$M/$LABEL.cpustat.start"
"$T/digest-snapshot.sh" truncate "$LABEL"
./shared/metrics-snapshot.sh start "$LABEL" >/dev/null
./shared/sampler.sh "$LABEL" 200 &
$CPUSH "$M/$LABEL.artillery-cpu" 200 3 &

# 지속 구간(부하 시작 +60~180s) 안에서 EXPLAIN을 1회 채취한다. 등록서가 허용한 예외이고
# 대표 쿼리만 본다 — 채취 자체가 부하에 얹히는 것을 최소화하기 위함이다.
if [ -n "$EXP_LABEL" ]; then
  ( sleep "$EXP_DELAY"; "$T/explain-set.sh" "$EXP_LABEL" "$EXP_ANALYZE" >/dev/null 2>&1 ) &
fi

npx artillery run "campaigns/2026-08-04_read-ceiling/scenarios/$SCEN" -o "$R/$LABEL.json" >/dev/null 2>&1
echo "### [$LABEL] POST $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
cgsnap > "$M/$LABEL.cpustat.end"
"$T/digest-snapshot.sh" dump "$LABEL"
wait
./shared/metrics-snapshot.sh report "$LABEL" >/dev/null
echo "### [$LABEL] DONE $(date '+%H:%M:%S')"
