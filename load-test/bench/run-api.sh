#!/usr/bin/env bash
# 사용법: ./run-api.sh <STATE라벨> <회차>   → ../reports/<STATE>-{place,course}-r<회차>.json
# 전제: 앱이 8082에서 벤치 DB를 바라보며 기동된 상태
set -euo pipefail
cd "$(dirname "$0")/.."   # load-test/
STATE="$1"; RUN="$2"
mkdir -p reports
npx artillery run -e bench scenarios/bookmark-place.yml  --output "reports/${STATE}-place-r${RUN}.json"
npx artillery run -e bench scenarios/bookmark-course.yml --output "reports/${STATE}-course-r${RUN}.json"
