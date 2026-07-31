#!/usr/bin/env bash
# GC·힙 보충 관측 — 라운드 전/후 JVM 카운터 diff
#
# ⚠️ 판정 근거가 아니다. 사전 등록한 다섯 항목(캠페인 README §5)에 GC는 없다.
# 사후에 지표를 늘려 판정에 쓰면 "결과에 맞춰 주장을 조정"하는 것이 되므로, 이것은
# 정성 관찰로만 기록한다 — "왜 B의 요청당 앱 CPU가 낮은가"의 설명 후보를 좁히는 용도다.
#
# 가설: A는 시 단위 요청마다 leaf 18개 스냅샷을 flatMap해 1,800개 리스트를 새로 만들고
# 메모리 정렬한다. B는 DB가 정렬해 20건만 만든다. 그렇다면 A의 할당률이 높아야 한다.
#
# 사용법: ./gc-probe.sh start <라벨>   /   ./gc-probe.sh report <라벨>
set -euo pipefail
cd "$(dirname "$0")"
OUT="../results/metrics"
LB="http://localhost:8082"

snap() {
  # 앱 2대를 LB로 번갈아 긁으면 어느 인스턴스 값인지 섞인다. 컨테이너에서 직접 읽는다.
  for c in solply-bench-app-1 solply-bench-app-2; do
    docker exec "$c" sh -c 'wget -qO- http://localhost:8082/actuator/prometheus' 2>/dev/null \
      | grep -E '^(jvm_gc_pause_seconds_count|jvm_gc_pause_seconds_sum|jvm_gc_memory_allocated_bytes_total|jvm_memory_used_bytes\{[^}]*area="heap")' \
      | sed "s/^/$c /"
  done
}

case "${1:?start|report}" in
  start)  snap > "$OUT/${2:?라벨}.gc.start" ; echo "GC 스냅샷 저장: ${2}.gc.start" ;;
  report)
    L="${2:?라벨}"; snap > "$OUT/$L.gc.end"
    python3 - "$OUT/$L.gc.start" "$OUT/$L.gc.end" "../results/reports/$L.json" <<'PY'
import sys, json, re, collections
def load(p):
    d = collections.defaultdict(float)
    for line in open(p):
        parts = line.split()
        if len(parts) < 3: continue
        _, metric, val = parts[0], " ".join(parts[1:-1]), parts[-1]
        name = metric.split("{")[0]
        try: d[name] += float(val)
        except ValueError: pass
    return d
a, b = load(sys.argv[1]), load(sys.argv[2])
req = json.load(open(sys.argv[3]))["aggregate"]["counters"]["http.requests"]
alloc = b["jvm_gc_memory_allocated_bytes_total"] - a["jvm_gc_memory_allocated_bytes_total"]
cnt   = b["jvm_gc_pause_seconds_count"] - a["jvm_gc_pause_seconds_count"]
sec   = b["jvm_gc_pause_seconds_sum"] - a["jvm_gc_pause_seconds_sum"]
print(f"  요청 {req}")
print(f"  할당 총량 {alloc/1e9:.2f} GB  → 요청당 {alloc/req/1024:.1f} KB")
print(f"  GC 횟수 {cnt:.0f}  총 정지 {sec*1000:.0f} ms  → 요청당 {sec*1000/req:.4f} ms")
print(f"  힙 사용(라운드 종료 시점, 앱2대 합) {b['jvm_memory_used_bytes']/1e6:.0f} MB")
PY
    ;;
esac
