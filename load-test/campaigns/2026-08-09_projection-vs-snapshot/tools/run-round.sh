#!/usr/bin/env bash
# 라운드 하나를 **모드 전환부터 계측 종료까지 한 번에** 돌린다.
# 2026-08-07_place-skeleton-cache/tools/run-step.sh의 계승판. 바뀐 것 셋:
#   ① 모드 전환(force-recreate)과 SQL 전환 검증이 라운드 안에 들어왔다 — 사전 등록 §7이
#      "매 라운드 = force-recreate → 전환 검증 → 시나리오"를 계약으로 못박았다
#   ② 라운드 끝에 60초 휴식을 넣는다 (같은 호스트에서 부하생성기가 돌아 회복 시간이 필요하다)
#   ③ 수집기 수명이 라운드 길이(110초) + 여유로 줄었다
#
# **부하가 도는 동안 이 호스트에서 다른 명령을 돌리지 않는다** — 부하생성기와 측정 대상이 같은
# 호스트다. 그래서 라운드는 이 스크립트 한 번의 호출로 끝난다.
#
# 사용: run-round.sh <모드> <라벨> <시나리오파일> [수집초] [휴식초]
set -uo pipefail
cd "$(dirname "$0")/../../.."                 # -> load-test/
CAMP="2026-08-09_projection-vs-snapshot"
export BENCH_CAMPAIGN="$CAMP"
MODE="${1:?모드 필요 (entity|projection|snapshot)}"
LABEL="${2:?라운드 라벨 필요}"
SCEN="${3:?시나리오 파일명 필요}"
DUR="${4:-250}"                               # 수집기 반복 횟수. 반복당 실측 ~1.3초라 라운드 210초를 덮는다
REST="${5:-60}"                               # 라운드 뒤 휴식(초)

M="campaigns/$CAMP/results/metrics"
R="campaigns/$CAMP/results/reports"
T="campaigns/$CAMP/tools"
B="campaigns/$CAMP/bench"
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

# ── ① 모드 전환 (fresh JVM) ────────────────────────────────────────────────
"$B/switch-mode.sh" "$MODE" | sed "s/^/[$LABEL] /" || exit 1

# ── ② 전환 검증 (실행된 SQL) — digest는 여기서 한 번 비워지고 라운드 끝에 다시 덤프된다 ──
"$B/verify-switch.sh" "$MODE" "$LABEL.switch" | sed "s/^/[$LABEL] /" || exit 2

echo "### [$LABEL/$MODE] PRE  $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"
date +%s.%N > "$M/$LABEL.t_pre"
cgsnap  > "$M/$LABEL.cpustat.start"
jvmsnap > "$M/$LABEL.jvm.start"
"$T/digest-snapshot.sh" truncate "$LABEL"
./shared/metrics-snapshot.sh start "$LABEL" >/dev/null

# ── 부하를 먼저 띄우고, **실제로 시작한 것을 확인한 뒤** 수집기를 붙인다 ──────────────
#
# 직전 판은 순서가 반대였다(수집기 → sleep 3 → 부하). 그 판으로 돈 라운드 하나를 폐기했다:
# `npx artillery`의 기동이 **8.4분** 걸려(첫 phase 22:50:35 vs 호출 22:42:12) 수집기가 부하가
# 시작되기도 전에 수명을 다 썼고, 측정 구간의 1초 샘플이 통째로 비었다. 원인은 측정 대상이
# 아니라 부하생성기의 기동 경로였다 — npx의 패키지 해석과 artillery의 원격 텔레메트리다.
# 그래서 둘을 없앤다:
#   ① `npx artillery` → `./node_modules/.bin/artillery` (로컬 바이너리 직접, 레지스트리 조회 없음)
#   ② `ARTILLERY_DISABLE_TELEMETRY=true` (기동 시 원격 호출 제거)
# 그리고 순서를 바꿔 **"Phase started"를 로그에서 확인한 뒤** 수집기를 띄운다. 놓치는 것은
# w1-cold의 앞 몇 초뿐이고 측정 구간은 라운드의 마지막 60초라 영향이 없다.
#
# 이것은 계측 결함 제거이고 세 모드에 동일하게 적용된다 — 측정 계약(부하·믹스·지표)은 그대로다.
date +%s.%N > "$M/$LABEL.t_artillery_start"
ARTILLERY_DISABLE_TELEMETRY=true ./node_modules/.bin/artillery run \
  "campaigns/$CAMP/scenarios/$SCEN" -o "$R/$LABEL.json" >"$M/$LABEL.artillery.log" 2>&1 &
P_ART=$!

for _ in $(seq 1 120); do
  grep -q "Phase started" "$M/$LABEL.artillery.log" 2>/dev/null && break
  kill -0 "$P_ART" 2>/dev/null || break
  sleep 1
done
grep -q "Phase started" "$M/$LABEL.artillery.log" 2>/dev/null || {
  echo "### [$LABEL/$MODE] 부하 기동 실패 — 라운드 폐기" >&2; kill "$P_ART" 2>/dev/null; exit 3; }
echo "### [$LABEL/$MODE] 부하 개시 확인 $(date '+%H:%M:%S') — 수집기 부착"

# 1초 수집기 4개. 전부 **스스로 N회 뒤 종료**한다 — 백그라운드 kill에 의존하지 않는다.
docker exec -i solply-bench-app   sh -s "$DUR" < "$T/collect-app.sh" > "$M/$LABEL.app1s.tsv"    2>"$M/$LABEL.app1s.err"    &
P_APP=$!
docker exec -i solply-bench-app   sh -s "$DUR" < "$T/collect-jvm.sh" > "$M/$LABEL.jvm1s.tsv"    2>"$M/$LABEL.jvm1s.err"    &
P_JVM=$!
docker exec -i solply-bench-mysql sh -s "$DUR" < "$T/collect-db.sh"  > "$M/$LABEL.db1s.tsv"     2>"$M/$LABEL.db1s.err"     &
P_DB=$!
"$T/collect-mysql-status.sh" "$DUR" > "$M/$LABEL.dbstat1s.tsv" 2>"$M/$LABEL.dbstat1s.err" &
P_ST=$!

wait "$P_ART"
date +%s.%N > "$M/$LABEL.t_artillery_end"
echo "### [$LABEL/$MODE] POST $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages: //')"

cgsnap  > "$M/$LABEL.cpustat.end"
jvmsnap > "$M/$LABEL.jvm.end"
"$T/digest-snapshot.sh" dump "$LABEL"
./shared/metrics-snapshot.sh report "$LABEL" >/dev/null

# 라운드 뒤 휴식 — 다음 라운드가 직전 라운드의 회복 구간에 겹치지 않게 한다.
# 수집기의 남은 수명(회복 구간 샘플링)과 **겹쳐서** 흘린다. 순서를 바꿔 각각 기다리면
# 라운드 하나가 1분 넘게 길어지는데, 그 1분은 아무 정보도 만들지 않는다.
sleep "$REST"
wait "$P_APP" "$P_JVM" "$P_DB" "$P_ST" 2>/dev/null
echo "### [$LABEL/$MODE] DONE $(date '+%H:%M:%S')  샘플 app=$(( $(wc -l < "$M/$LABEL.app1s.tsv") - 1 )) db=$(( $(wc -l < "$M/$LABEL.db1s.tsv") - 1 )) jvm=$(( $(wc -l < "$M/$LABEL.jvm1s.tsv") - 1 )) dbstat=$(( $(wc -l < "$M/$LABEL.dbstat1s.tsv") - 1 ))"
echo "### [$LABEL/$MODE] REST 완료 $(date '+%H:%M:%S')"
