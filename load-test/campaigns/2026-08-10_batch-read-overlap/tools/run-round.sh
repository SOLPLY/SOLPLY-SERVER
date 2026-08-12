#!/usr/bin/env bash
# 라운드 하나의 타임라인 실행기 — 사전 등록 README §6을 그대로 실행한다.
# 사용: BENCH_CAMPAIGN=2026-08-10_batch-read-overlap tools/run-round.sh <라벨> <separated|colocated|storm>
#
#   separated  B팔(현행) — bench/20-batch-separated.sql  (place_stats에 착지)
#   colocated  A팔(흉내) — bench/21-batch-colocated.sql  (places 더미 컬럼에 착지)
#   storm      P팔(양성 대조) — bench/30-hot-page-storm.sh 15  (계측 검출력 게이트)
#
# **부하가 도는 동안 이 호스트에서 다른 명령을 돌리지 않는다** — 부하생성기와 측정 대상이 같은
# 호스트다. 라운드는 이 스크립트 한 번의 호출로 끝난다.
#
# ── t0의 정의 ────────────────────────────────────────────────────────────────
# 모든 창 오프셋(+160/+175/+190)은 t0에 매인다. t0는 **이 스크립트를 부른 시각이 아니라 artillery
# 로그에 "Phase started"가 찍힌 시각**이다. 08-09 캠페인에서 `npx artillery`의 기동이 8.4분 걸려
# 라운드 하나를 폐기한 전례가 있다(패키지 해석 + 원격 텔레메트리). 호출 시각을 t0로 잡으면 배치가
# 사다리 구간 한복판에 떨어져 라운드가 통째로 무의미해진다. 그래서 셋을 고정한다:
#   ① `npx` 대신 ./node_modules/.bin/artillery (로컬 바이너리 직접)
#   ② ARTILLERY_DISABLE_TELEMETRY=true
#   ③ "Phase started" 확인 뒤에 t0를 찍고, 그때 스냅샷·샘플러를 붙인다
# 놓치는 것은 사다리 첫 몇 초뿐이고, 창은 전부 t0+150 이후다.
#
# 전제: (1) tools/enable-instrumentation.sql이 이 MySQL 인스턴스에 이미 적용돼 있어야 한다
#           (force-recreate 뒤에는 다시 적용). 안 켜져 있으면 waits Δ가 전부 0으로만 나온다.
#       (2) 앱은 skeleton-source=entity + count-cron/score-cron 비활성으로 떠 있어야 한다(§3·§7).
#           이 스크립트는 라운드 끝에 cron 발화 흔적을 로그에서 검사한다.
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> load-test/

usage() {
  echo "usage: BENCH_CAMPAIGN=<캠페인 디렉터리명> $0 <label> <separated|colocated|storm>" >&2
  exit 1
}
[ -n "${BENCH_CAMPAIGN:-}" ] || { echo "[round] BENCH_CAMPAIGN 환경변수 필요 (캠페인 디렉터리명)" >&2; usage; }
LABEL="${1:-}"
MODE="${2:-}"
[ -n "$LABEL" ] && [ -n "$MODE" ] || usage

export BENCH_CAMPAIGN                  # 하위 도구(shared/sampler.sh 등)도 같은 캠페인에 쓴다
CAMP="$BENCH_CAMPAIGN"
M="campaigns/$CAMP/results/metrics"
R="campaigns/$CAMP/results/reports"
T="campaigns/$CAMP/tools"
B="campaigns/$CAMP/bench"
# 스로틀 게이트(README §6) 발동 시 SCEN_FILE로 부하점을 바꾼다. 팔 간 비교는 동일 부하점에서만.
SCEN="campaigns/$CAMP/scenarios/${SCEN_FILE:-overlap-fixed-240.yml}"
mkdir -p "$M" "$R"

case "$MODE" in
  separated) FIRE_FILE="$B/20-batch-separated.sql" ;;
  colocated) FIRE_FILE="$B/21-batch-colocated.sql" ;;
  storm)     FIRE_FILE="$B/30-hot-page-storm.sh" ;;
  *)         echo "[round] 알 수 없는 모드: $MODE" >&2; usage ;;
esac

# 프리플라이트 — 발화 대상이 없으면 부하를 걸기 **전에** 실패한다. t0+190에서야 알면 라운드 하나를 버린다.
[ -f "$SCEN" ]      || { echo "[round] 시나리오 없음: $SCEN" >&2; exit 1; }
[ -f "$FIRE_FILE" ] || { echo "[round] 발화 파일 없음: $FIRE_FILE" >&2; exit 1; }
[ "$MODE" != "storm" ] || [ -x "$FIRE_FILE" ] || { echo "[round] 실행 권한 없음: chmod +x $FIRE_FILE" >&2; exit 1; }
[ -x ./node_modules/.bin/artillery ] || { echo "[round] artillery 바이너리 없음 (load-test/에서 npm i)" >&2; exit 1; }

MYSQL_EXEC=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd solply_bench_db)

# cgroup이 CPU의 정본이다 — docker stats는 고동시성에서 상한 초과를 보고한다.
cpusnap() {  # $1: start|end
  docker exec solply-bench-app   cat /sys/fs/cgroup/cpu.stat > "$M/$LABEL.cpustat.app.$1"   2>/dev/null
  docker exec solply-bench-mysql cat /sys/fs/cgroup/cpu.stat > "$M/$LABEL.cpustat.mysql.$1" 2>/dev/null
}

# 창 경계를 파일로 남긴다 — 1초 샘플러(undo/samples)를 창에 맞춰 자르려면 경계 시각이 있어야 한다.
mark() { printf '%s\t%s\t%s\n' "$(date +%s.%N)" "$(( $(date +%s) - T0 ))" "$1" >> "$M/$LABEL.timeline.tsv"; }

# 절대 시각 기준으로 기다린다 — sleep을 누적하면 각 단계의 실행 시간만큼 창이 밀린다.
sleep_until() {
  local off="$1" now target
  target=$((T0 + off))
  now=$(date +%s)
  if [ "$now" -lt "$target" ]; then
    sleep $((target - now))
  else
    echo "[round] ⚠ 오프셋 +${off}s 지각 $((now - target))초 — 창이 밀렸다" >&2
  fi
}

echo "### [$LABEL/$MODE] 부하 기동 $(date '+%H:%M:%S')  load: $(uptime | sed 's/.*load averages*: //')"
: > "$M/$LABEL.timeline.tsv"

ARTILLERY_DISABLE_TELEMETRY=true ./node_modules/.bin/artillery run \
  "$SCEN" -o "$R/$LABEL.json" > "$M/$LABEL.artillery.log" 2>&1 &
P_ART=$!

for _ in $(seq 1 180); do
  grep -q "Phase started" "$M/$LABEL.artillery.log" 2>/dev/null && break
  kill -0 "$P_ART" 2>/dev/null || break
  sleep 1
done
grep -q "Phase started" "$M/$LABEL.artillery.log" 2>/dev/null || {
  echo "### [$LABEL/$MODE] 부하 기동 실패 — 라운드 폐기" >&2
  kill "$P_ART" 2>/dev/null || true
  exit 3
}

T0=$(date +%s)
mark "t0_phase_started"
echo "### [$LABEL/$MODE] t0=$T0 $(date '+%H:%M:%S') — 스냅샷·샘플러 부착"

cpusnap start
./shared/metrics-snapshot.sh start "$LABEL" > /dev/null
# 280초: 라운드 270초 + 여유. 샘플러는 스스로 종료한다 — kill에 의존하지 않는다.
./shared/sampler.sh "$LABEL" 280 > "$M/$LABEL.sampler.log" 2>&1 &
P_SAMP=$!
"$T/undo-sampler.sh" "$LABEL" 280 > "$M/$LABEL.undo.log" 2>&1 &
P_UNDO=$!

# ── 대조 창 (t0+160 ~ t0+175) — 같은 라운드 안의 바닥값 ─────────────────────────
sleep_until 160
mark "control_window_open"
"$T/waits-snapshot.sh"  start    "C-$LABEL"
"$T/digest-window.sh"   truncate "control-$LABEL"

sleep_until 175
mark "control_window_close"
"$T/digest-window.sh"   dump     "control-$LABEL"
"$T/waits-snapshot.sh"  report   "C-$LABEL"

# ── 배치 창 (t0+190 ~ 커밋 직후) ────────────────────────────────────────────────
# census(pre)를 **먼저** 끝내고 창을 연다. 전수 조회는 수 초 걸리는데, 그 비용이 창 안에 들어가면
# 배치 창의 digest·waits가 census의 것까지 세게 된다.
sleep_until 190
mark "census_pre_start"
"$T/dirty-census.sh" "pre-$LABEL"
mark "batch_window_open"
"$T/waits-snapshot.sh"  start    "B-$LABEL"
"$T/digest-window.sh"   truncate "batch-$LABEL"

mark "batch_fire_start"
BATCH_EPOCH_START=$(date +%s.%N)
set +e
if [ "$MODE" = "storm" ]; then
  { time "$FIRE_FILE" 15 ; } > "$M/$LABEL.batch.log" 2> "$M/$LABEL.batch-duration.txt"
else
  { time "${MYSQL_EXEC[@]}" < "$FIRE_FILE" ; } > "$M/$LABEL.batch.log" 2> "$M/$LABEL.batch-duration.txt"
fi
BATCH_RC=$?
set -e
BATCH_EPOCH_END=$(date +%s.%N)
mark "batch_fire_end"

# `time`의 출력 형식은 셸마다 다르다 — 판정에 쓰는 소요는 epoch 차이로 따로 적는다.
{
  printf 'mode=%s\nfire=%s\nexit_code=%d\n' "$MODE" "$FIRE_FILE" "$BATCH_RC"
  awk -v a="$BATCH_EPOCH_START" -v b="$BATCH_EPOCH_END" \
      'BEGIN { printf "epoch_start=%s\nepoch_end=%s\nelapsed_s=%.3f\n", a, b, b - a }'
} >> "$M/$LABEL.batch-duration.txt"
[ "$BATCH_RC" -eq 0 ] || echo "### [$LABEL/$MODE] ⚠ 배치 발화 실패 (exit=$BATCH_RC) — $M/$LABEL.batch.log 확인" >&2

# 커밋 직후 — 순서가 계약이다. digest·waits를 먼저 닫고 census(post)를 마지막에 둔다
# (census 자체가 무거워 그 뒤 구간의 레이턴시는 어느 창에도 넣지 않는다).
"$T/digest-window.sh"   dump   "batch-$LABEL"
"$T/waits-snapshot.sh"  report "B-$LABEL"
mark "batch_window_close"
"$T/dirty-census.sh" "post-$LABEL"
mark "census_post_done"

# ── 라운드 종료 ────────────────────────────────────────────────────────────────
ART_RC=0
wait "$P_ART" || ART_RC=$?
mark "artillery_end"
echo "### [$LABEL/$MODE] 부하 종료 $(date '+%H:%M:%S') (exit=$ART_RC)  load: $(uptime | sed 's/.*load averages*: //')"

./shared/metrics-snapshot.sh report "$LABEL" > /dev/null
cpusnap end

# ── 무효 조건 검사: 실배치 cron 발화 ──────────────────────────────────────────
# cron 비활성(`-`)이 안 먹었으면 라운드 구간에 통계 배치가 제멋대로 끼어든다. 그러면 배치 창의
# 더티·언두가 수동 발화의 것인지 cron의 것인지 구분할 수 없다 — 구간을 잘라내지 않고 **라운드를 폐기**한다.
ROUND_WINDOW=$(( $(date +%s) - T0 + 10 ))
CRON_HITS=$(docker logs solply-bench-app --since "${ROUND_WINDOW}s" 2>&1 \
            | grep -E "인기순 카운트 배치|인기점수 배치" || true)
if [ -n "$CRON_HITS" ]; then
  printf '%s\n' "$CRON_HITS" > "$M/$LABEL.cron-contamination.txt"
  echo "################################################################"
  echo "###  ⚠⚠⚠  라운드 무효: 실배치 cron이 발화했다  ($LABEL)"
  echo "###  count-cron/score-cron 비활성 설정이 안 먹었다."
  echo "###  BENCH_APP_JSON + up -d --force-recreate 로 다시 띄우고 라운드를 재실행한다."
  echo "###  근거: $M/$LABEL.cron-contamination.txt"
  echo "################################################################"
  printf '%s\n' "$CRON_HITS" | sed 's/^/###  /'
fi

wait "$P_SAMP" "$P_UNDO" 2>/dev/null || true
echo "### [$LABEL/$MODE] DONE $(date '+%H:%M:%S')  배치소요=$(grep '^elapsed_s=' "$M/$LABEL.batch-duration.txt" | tail -1)"
echo "### [$LABEL/$MODE] 다음 라운드 전 60초 휴식 (README §6)"
[ -z "$CRON_HITS" ] || exit 9
