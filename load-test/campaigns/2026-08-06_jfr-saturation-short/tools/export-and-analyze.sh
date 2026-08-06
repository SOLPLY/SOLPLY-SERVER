#!/usr/bin/env bash
# JFR binary에서 필요한 이벤트만 임시 JSON으로 풀고 구간 분석을 재생성한다.
# 사용: ./tools/export-and-analyze.sh [label]
set -euo pipefail

CAMP_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="${1:-jfr-short-3}"
PROFILE="$CAMP_ROOT/results/profile"
REPORTS="$CAMP_ROOT/results/reports"
EVENTS_JSON="$(mktemp -t solply-jfr-events.XXXXXX.json)"
trap 'rm -f "$EVENTS_JSON"' EXIT

docker run --rm -v "$PROFILE:/profile:ro" eclipse-temurin:21-jre-alpine \
  /opt/java/openjdk/bin/jfr print --json \
  --events 'jdk.ExecutionSample,jdk.NativeMethodSample,jdk.GarbageCollection,jdk.GCPhasePause,jdk.GCCPUTime,jdk.JavaThreadStatistics,jdk.ThreadAllocationStatistics,jdk.JavaMonitorEnter,jdk.CPULoad' \
  "/profile/$LABEL.jfr" > "$EVENTS_JSON"

python3 "$CAMP_ROOT/tools/analyze-jfr.py" "$EVENTS_JSON" "$REPORTS" "$LABEL" \
  > "$PROFILE/$LABEL.analysis.txt"
cat "$PROFILE/$LABEL.analysis.txt"
