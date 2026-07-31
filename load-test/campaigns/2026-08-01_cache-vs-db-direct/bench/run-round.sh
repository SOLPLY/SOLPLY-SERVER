#!/usr/bin/env bash
# 라운드 1회 실행 — 3단 지표(결과·자원·원인)를 한 번에 건다.
#
# 시나리오는 baseline(2026-07-30) 것을 그대로 재사용한다. 이 캠페인 전용으로 새로 쓰지 않는
# 이유는 비교 가능성이다 — 시나리오가 다르면 baseline의 "요청당 14.2 statements"와 대조할 수 없다.
#
# 사용법: ./run-round.sh <라벨>        예) ./run-round.sh cache-r1
set -euo pipefail

cd "$(dirname "$0")/../../.."          # -> load-test/
LABEL="${1:?라운드 라벨 (예: cache-r1)}"
export BENCH_CAMPAIGN=2026-08-01_cache-vs-db-direct

SCENARIO=campaigns/2026-07-30_place-popular-baseline/scenarios/place-list-popular.yml
REPORT=campaigns/$BENCH_CAMPAIGN/results/reports/$LABEL.json

echo "── $LABEL 시작 ($(date +%H:%M:%S))"
./shared/metrics-snapshot.sh start "$LABEL" >/dev/null
./shared/sampler.sh "$LABEL" 200 >/dev/null 2>&1 &
SAMPLER=$!

# artillery 출력을 파일로 받는다. 파이프에 head를 물리면 SIGPIPE로 라운드가 잘릴 수 있다.
npx artillery run "$SCENARIO" -o "$REPORT" > "campaigns/$BENCH_CAMPAIGN/results/metrics/$LABEL.artillery.log" 2>&1

wait $SAMPLER || true
./shared/metrics-snapshot.sh report "$LABEL" >/dev/null

python3 - "$REPORT" <<'PY'
import json,sys
a = json.load(open(sys.argv[1]))['aggregate']
c, s = a['counters'], a['summaries'].get('http.response_time', {})
req  = c.get('http.requests', 0)
ok   = c.get('http.codes.200', 0)
errs = {k: v for k, v in c.items() if k.startswith('errors.') or (k.startswith('http.codes.') and k != 'http.codes.200')}
dur  = (a['lastMetricAt'] - a['firstMetricAt']) / 1000
print(f"  요청 {req}  성공 {ok}  성공률 {ok/req*100:.2f}%  소요 {dur:.0f}s  RPS {req/dur:.1f}")
print(f"  p50 {s.get('p50')}ms   p95 {s.get('p95')}ms   (p99·max는 폐기 지표 — 인용 금지)")
if errs: print("  ⚠️ 오류:", errs)
PY
echo "── $LABEL 완료 ($(date +%H:%M:%S))"
