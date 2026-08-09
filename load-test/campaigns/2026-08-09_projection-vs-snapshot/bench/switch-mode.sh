#!/usr/bin/env bash
# 골격 출처 모드를 바꿔 앱 컨테이너를 **재생성**하고 기동을 기다린다.
#
# `docker restart`가 아니라 `up -d --force-recreate`인 이유: 환경변수(SPRING_APPLICATION_JSON)는
# 컨테이너 생성 시점에 박히므로 restart로는 반영되지 않는다 (load-test/README.md §5 함정).
# 부수 효과로 매 라운드가 fresh JVM에서 시작된다 — 사전 등록 §7의 "라운드마다 재생성"이
# 이 스크립트 하나로 충족된다.
#
# ⚠️ --remove-orphans 금지: docker/ 안 compose 5개가 프로젝트명을 공유한다.
#
# 사용: ./switch-mode.sh {entity|projection|snapshot}
set -euo pipefail
MODE="${1:?mode 를 지정하라 (entity|projection|snapshot)}"
case "$MODE" in entity|projection|snapshot) ;; *) echo "알 수 없는 모드: $MODE" >&2; exit 1 ;; esac

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
cd "$ROOT"

echo "[switch] $MODE 로 앱 재생성  $(date '+%H:%M:%S')"
BENCH_APP_JSON="{\"solply.place-list.skeleton-source\":\"$MODE\"}" \
  docker compose -f docker/docker-compose.bench.yml up -d --force-recreate bench-app >/dev/null 2>&1

# 기동 완료까지 대기. 로그의 "Started ..."를 본다 — 컨테이너 running ≠ 요청 수용 가능.
for _ in $(seq 1 120); do
  if docker logs solply-bench-app 2>&1 | grep -q "Started SolplyServerApplication"; then break; fi
  sleep 1
done
docker logs solply-bench-app 2>&1 | grep -q "Started SolplyServerApplication" || {
  echo "[switch] 기동 실패 — 로그 확인 필요" >&2; exit 1; }

# 컨테이너에 실제로 박힌 값. 설정값 확인은 여기까지고, **전환의 증거는 SQL이다**(verify-switch.sh).
echo "[switch] SPRING_APPLICATION_JSON = $(docker exec solply-bench-app printenv SPRING_APPLICATION_JSON)"
echo "[switch] 골격 로그: $(docker logs solply-bench-app 2>&1 | grep -E '골격 스냅샷' | tail -1)"

# edge가 앱을 다시 잡을 시간 + JVM이 초기 프리로드를 마칠 시간
sleep 5
echo "[switch] 준비 완료  $(date '+%H:%M:%S')"
