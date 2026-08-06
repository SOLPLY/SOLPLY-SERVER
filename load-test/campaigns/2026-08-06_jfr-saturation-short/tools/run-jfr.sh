#!/usr/bin/env bash
# 기존 요청 믹스를 60 -> 120으로 충분히 예열한 뒤 300 -> 360 arrivals/s로 실행하고 JFR을 회수한다.
# 사용: ./tools/run-jfr.sh [label]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
LOAD_ROOT="$REPO_ROOT/load-test"
CAMP_ROOT="$LOAD_ROOT/campaigns/2026-08-06_jfr-saturation-short"
BASE_COMPOSE="$REPO_ROOT/docker/docker-compose.bench.yml"
JFR_COMPOSE="$CAMP_ROOT/docker-compose.jfr.yml"
BASE_SCENARIO="campaigns/2026-08-06_app-saturation-diagnosis/scenarios/saturation-step.yml"
LABEL="${1:-jfr-short-$(date +%H%M%S)}"
RESULTS="$CAMP_ROOT/results"
REPORTS="$RESULTS/reports"
METRICS="$RESULTS/metrics"
PROFILE="$RESULTS/profile"
APP="solply-bench-app"

mkdir -p "$REPORTS" "$METRICS" "$PROFILE"

restore_app() {
  # JFR override 없이 원래 bench-app을 다시 만든다. 고정 IP와 2 vCPU/2 GiB는 base compose가 복원한다.
  docker compose -f "$BASE_COMPOSE" up -d --no-deps --force-recreate bench-app >/dev/null
}
trap restore_app EXIT

wait_api() {
  local token status
  token="$(sed -n '2p' "$LOAD_ROOT/data/users.csv" | tr -d '\r')"
  for _ in $(seq 1 90); do
    status="$(curl -sS -o /dev/null -w '%{http_code}' \
      -H "Authorization: Bearer $token" \
      'http://localhost:8082/api/places?townId=301&isBookmarkSearch=false&sort=POPULAR&size=20' || true)"
    if [[ "$status" == "200" ]]; then return 0; fi
    sleep 1
  done
  echo "app API did not become ready" >&2
  return 1
}

run_stage() {
  local name="$1" rate="$2" duration="$3" override
  override="{\"config\":{\"phases\":[{\"name\":\"$name\",\"duration\":$duration,\"arrivalRate\":$rate}]}}"
  date +%s.%N > "$METRICS/$LABEL.$name.start"
  (
    cd "$LOAD_ROOT"
    npx artillery run --overrides "$override" "$BASE_SCENARIO" \
      -o "campaigns/2026-08-06_jfr-saturation-short/results/reports/$LABEL.$name.json" \
      > "campaigns/2026-08-06_jfr-saturation-short/results/metrics/$LABEL.$name.artillery.log" 2>&1
  )
  date +%s.%N > "$METRICS/$LABEL.$name.end"
}

docker inspect "$APP" --format 'before image={{.Config.Image}} nano={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}} restart={{.RestartCount}}' \
  > "$METRICS/$LABEL.container.txt"

# 앱만 동일 JRE 이미지 + JFR 시작 인자로 재생성한다. MySQL과 nginx는 유지한다.
docker compose -f "$BASE_COMPOSE" -f "$JFR_COMPOSE" up -d --no-deps --force-recreate bench-app >/dev/null
wait_api
docker inspect "$APP" --format 'profile image={{.Config.Image}} nano={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}} restart={{.RestartCount}}' \
  >> "$METRICS/$LABEL.container.txt"
docker exec "$APP" sh -c '/opt/java/openjdk/bin/java --list-modules | grep jdk.jfr; ls -l /tmp/solply-saturation.jfr' \
  > "$PROFILE/$LABEL.capability.txt"

# 재기동 직후 120/s를 바로 넣으면 JIT·쿼리 캐시가 차가운 상태에서 대기열이 먼저 생겨 워밍업
# 자체가 회복되지 않았다(폐기 라운드에서 확인). 낮은 부하로 먼저 정상 p50을 만든 뒤 올린다.
# 워밍업은 프로파일에 들어가지만 분석 시간창에서는 제외한다.
run_stage warmup-60 60 30
sleep 3
run_stage warmup-120 120 30
sleep 3
run_stage load-300 300 20
sleep 3
run_stage load-360 360 20
sleep 3

# SIGTERM 정상 종료로 dumponexit 파일을 완결한 다음, 컨테이너가 없어지기 전에 회수한다.
docker stop -t 30 "$APP" >/dev/null
docker cp "$APP:/tmp/solply-saturation.jfr" "$PROFILE/$LABEL.jfr"
docker run --rm -v "$PROFILE:/profile:ro" eclipse-temurin:21-jre-alpine \
  /opt/java/openjdk/bin/jfr summary "/profile/$LABEL.jfr" > "$PROFILE/$LABEL.summary.txt"

# trap이 원래 앱을 복구하고 나면 호출자가 별도 분석을 수행한다.
