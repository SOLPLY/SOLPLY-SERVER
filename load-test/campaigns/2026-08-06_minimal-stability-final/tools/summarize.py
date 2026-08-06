#!/usr/bin/env python3
"""라운드 하나의 3계층 지표를 한 화면으로 모은다 — 사전 등록(README §5·§6) 판정용.

사용: python3 tools/summarize.py <라벨> [<라벨> ...]

여기서 계산하는 것은 전부 '원자료에서 유도 가능한 값'이다. 원자료는 results/ 아래에 그대로
남아 있으므로 이 스크립트가 없어도 재계산할 수 있다.
"""
import json, os, sys, re

CAMP = os.path.join(os.path.dirname(__file__), "..")
R = os.path.join(CAMP, "results")

# 라운드 프로파일: 라벨 -> (warmup초, ramp초, steady초, steady 도착률, ramp 시작률)
PROFILE = {
    "warmup":        (20, 20, 60, 120, 30),
    "r-a-120":       (20, 20, 60, 120, 30),
    "r-a-120-rep":   (20, 20, 60, 120, 30),
    "r-b-300":       (20, 20, 60, 300, 60),
    "r-b-300-rep":   (20, 20, 60, 300, 60),
    "r-c-120-batch": (20, 20, 60, 120, 30),
    # 부록 D — 앱 CPU 상한만 4 vCPU로 올린 대조 라운드. 프로파일은 B와 완전히 같다.
    "warmup-app4":   (20, 20, 60, 120, 30),
    "r-d-300-app4":  (20, 20, 60, 300, 60),
}


def planned_arrivals(label):
    w, rp, st, rate, start = PROFILE[label]
    return w * start + rp * (start + rate) / 2 + st * rate


def read_cpustat(path):
    """cgroup 스냅샷 파싱 -> {container: {key: value}}"""
    out, cur = {}, None
    if not os.path.exists(path):
        return out
    for line in open(path):
        line = line.strip()
        if line.startswith("== "):
            cur = line[3:]
            out[cur] = {}
        elif cur and ":" in line:
            k, v = line.split(":", 1)
            out[cur][k.strip()] = v.strip()
        elif cur and " " in line:
            k, v = line.split(None, 1)
            out[cur][k.strip()] = v.strip()
    return out


def prom(path, name, labels_contains=None):
    """prometheus 텍스트에서 metric 값들을 모은다."""
    vals = []
    if not os.path.exists(path):
        return vals
    for line in open(path):
        if line.startswith("#") or not line.startswith(name):
            continue
        m = re.match(r"^(\S+?)(\{.*\})?\s+([-\d.eE+]+)$", line.strip())
        if not m:
            continue
        metric, lbl, val = m.group(1), m.group(2) or "", m.group(3)
        if metric != name:
            continue
        if labels_contains and labels_contains not in lbl:
            continue
        try:
            vals.append((lbl, float(val)))
        except ValueError:
            pass
    return vals


def status_diff(label):
    """SHOW GLOBAL STATUS start/end diff (숫자 항목만)."""
    s, e = f"{R}/metrics/{label}.start", f"{R}/metrics/{label}.end"
    if not (os.path.exists(s) and os.path.exists(e)):
        return {}
    def load(p):
        d = {}
        for line in open(p):
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2:
                d[parts[0]] = parts[1]
        return d
    a, b = load(s), load(e)
    out = {}
    for k in b:
        try:
            out[k] = int(b[k]) - int(a.get(k, 0))
        except ValueError:
            pass
    return out


def digest_rows(label):
    p = f"{R}/digests/{label}.digest.tsv"
    if not os.path.exists(p):
        return []
    lines = open(p).read().splitlines()
    if not lines:
        return []
    hdr = lines[0].split("\t")
    rows = []
    for ln in lines[1:]:
        f = ln.split("\t")
        if len(f) != len(hdr):
            continue
        rows.append(dict(zip(hdr, f)))
    return rows


def report(label):
    print("=" * 78)
    print(f"# 라운드 {label}")
    print("=" * 78)

    rep = f"{R}/reports/{label}.json"
    d = json.load(open(rep))
    agg = d["aggregate"]
    c = agg["counters"]
    created = c.get("vusers.created", 0)
    plan = planned_arrivals(label)
    print(f"[결과] 도착: 계획 {plan:.0f} / 생성 {created} = {created/plan*100:.2f}%")
    print(f"[결과] 시나리오 완료 {c.get('vusers.completed',0)} / 실패 {c.get('vusers.failed',0)}")
    print(f"[결과] HTTP 요청 {c.get('http.requests',0)} / 응답 {c.get('http.responses',0)}")
    codes = {k: v for k, v in c.items() if k.startswith("http.codes.")}
    errs = {k: v for k, v in c.items() if k.startswith("errors.")}
    print(f"[결과] 코드 {codes}   에러 {errs}")
    s = agg["summaries"].get("http.response_time", {})
    print(f"[결과] 전체 응답시간 p50 {s.get('p50')} p95 {s.get('p95')} p99 {s.get('p99')} max {s.get('max')}")

    # 엔드포인트별
    print("[결과] 요청별 p50/p95/p99 (전 구간):")
    for k, v in sorted(agg["summaries"].items()):
        if k.startswith("plugins.metrics-by-endpoint.response_time."):
            nm = k.split("response_time.")[1]
            print(f"        {nm:26s} n={v['count']:6d}  p50 {v['p50']:7.1f}  p95 {v['p95']:7.1f}  p99 {v['p99']:7.1f}  max {v['max']:7.1f}")

    # 정상 구간(steady) — 마지막 60초 버킷들
    ints = d.get("intermediate", [])
    w, rp, st, rate, _ = PROFILE[label]
    steady = [b for b in ints if b.get("firstCounterAt")]
    if steady:
        t0 = steady[0]["firstCounterAt"]
        steady = [b for b in ints if b["firstCounterAt"] - t0 >= (w + rp) * 1000]
    def merge(bs, key):
        vals = [b["summaries"].get(key) for b in bs]
        vals = [v for v in vals if v]
        return vals
    print(f"[결과] 정상 구간 버킷 {len(steady)}개 (백분위는 재집계 불가 — 버킷별 값과 중앙값을 함께 싣는다)")
    p50s, p95s, p99s, rates = [], [], [], []
    for b in steady:
        v = b["summaries"].get("http.response_time")
        r = b["rates"].get("http.request_rate")
        if v:
            p50s.append(v["p50"]); p95s.append(v["p95"]); p99s.append(v["p99"])
        if r:
            rates.append(r)
    med = lambda xs: sorted(xs)[len(xs)//2] if xs else None
    print(f"        버킷 p50 {p50s}")
    print(f"        버킷 p95 {p95s}")
    print(f"        버킷 p99 {p99s}")
    print(f"        버킷 처리율 {rates}  중앙값 {med(rates)}")
    print(f"        중앙값: p50 {med(p50s)}  p95 {med(p95s)}  p99 {med(p99s)}")

    # 정상 구간만의 요청별 백분위 — 버킷별 값의 중앙값 (백분위는 재집계 불가하므로 평균 금지)
    print("[결과] 정상 구간 요청별 백분위 (버킷 중앙값):")
    names = sorted({k.split("response_time.")[1] for b in steady
                    for k in b["summaries"] if k.startswith("plugins.metrics-by-endpoint.response_time.")})
    for nm in names:
        key = f"plugins.metrics-by-endpoint.response_time.{nm}"
        vs = [b["summaries"][key] for b in steady if key in b["summaries"]]
        if not vs:
            continue
        print(f"        {nm:26s} n={sum(v['count'] for v in vs):6d}"
              f"  p50 {med([v['p50'] for v in vs]):7.1f}"
              f"  p95 {med([v['p95'] for v in vs]):7.1f}"
              f"  p99 {med([v['p99'] for v in vs]):7.1f}"
              f"  max {max(v['max'] for v in vs):7.1f}")

    # 자원 — cgroup
    a, b = read_cpustat(f"{R}/metrics/{label}.cpustat.start"), read_cpustat(f"{R}/metrics/{label}.cpustat.end")
    print("[자원] cgroup CPU (정본):")
    for cont in a:
        if cont not in b:
            continue
        try:
            du = int(b[cont]["usage_usec"]) - int(a[cont]["usage_usec"])
            np_ = int(b[cont]["nr_periods"]) - int(a[cont]["nr_periods"])
            nt = int(b[cont]["nr_throttled"]) - int(a[cont]["nr_throttled"])
            tt = int(b[cont]["throttled_usec"]) - int(a[cont]["throttled_usec"])
        except (KeyError, ValueError):
            continue
        quota = a[cont].get("cpu.max", "? ?").split()
        cores = float(quota[0]) / float(quota[1]) if quota[0] != "max" else None
        # 관측 창 = 라운드 소요(대략 nr_periods × 100ms)
        window_us = np_ * 100_000 if np_ else None
        util = (du / window_us * 100) if window_us else 0.0
        quota_pct = (util / (cores * 100) * 100) if (util and cores) else 0.0
        print(f"        {cont:20s} usage {du/1e6:8.2f}s  창 {(window_us or 0)/1e6:6.1f}s"
              f"  코어상한 {cores}  사용률 {util:6.1f}% of 1core"
              f"  = {quota_pct:5.1f}% of quota"
              f"  throttled {nt}/{np_} periods ({nt/np_*100 if np_ else 0:.1f}%) {tt/1e6:.2f}s")
        mp = b[cont].get("memory.peak", "NA"); mm = b[cont].get("memory.max", "NA")
        try:
            print(f"        {'':20s} memory.peak {int(mp)/2**20:.0f} MiB / max {int(mm)/2**20:.0f} MiB"
                  f"  restart_count {b[cont].get('restart_count')}")
        except ValueError:
            print(f"        {'':20s} memory.peak {mp} / max {mm}  restart_count {b[cont].get('restart_count')}")

    # 자원 — JVM
    js, je = f"{R}/metrics/{label}.jvm.start", f"{R}/metrics/{label}.jvm.end"
    def gcsum(p):
        tot = {lbl: v for lbl, v in prom(p, "jvm_gc_pause_seconds_sum")}
        cnt = {lbl: v for lbl, v in prom(p, "jvm_gc_pause_seconds_count")}
        mx = {lbl: v for lbl, v in prom(p, "jvm_gc_pause_seconds_max")}
        return tot, cnt, mx
    ts, cs, ms = gcsum(js); te, ce, me = gcsum(je)
    print("[자원] JVM GC (actuator/prometheus):")
    for lbl in te:
        dsum = te[lbl] - ts.get(lbl, 0)
        dcnt = ce[lbl] - cs.get(lbl, 0)
        if dcnt == 0 and dsum == 0:
            continue
        short = re.sub(r'application="[^"]*",?', "", lbl)
        print(f"        {short}  +{dcnt:.0f}회  +{dsum*1000:.0f}ms  구간 max {me.get(lbl,0)*1000:.0f}ms")
    heap = [v for lbl, v in prom(je, "jvm_memory_used_bytes", 'area="heap"')]
    heapmax = [v for lbl, v in prom(je, "jvm_memory_max_bytes", 'area="heap"')]
    print(f"        heap used(종료 시점) {sum(heap)/2**20:.0f} MiB / max {sum(x for x in heapmax if x>0)/2**20:.0f} MiB")
    hik = {re.sub(r'application="[^"]*",?', "", l): v for l, v in prom(je, "hikaricp_connections_active")}
    hikp = {re.sub(r'application="[^"]*",?', "", l): v for l, v in prom(je, "hikaricp_connections_pending")}
    print(f"        hikari active(종료 시점) {hik}  pending {hikp}")

    # 원인 — MySQL 상태
    sd = status_diff(label)
    keys = ["Questions", "Select_scan", "Created_tmp_disk_tables", "Created_tmp_tables",
            "Innodb_row_lock_waits", "Innodb_row_lock_time", "Innodb_deadlocks",
            "Innodb_buffer_pool_read_requests", "Innodb_buffer_pool_reads",
            "Innodb_data_reads", "Innodb_data_writes", "Aborted_connects", "Connection_errors_max_connections"]
    print("[원인] MySQL 전역 카운터 증가분:")
    for k in keys:
        if k in sd:
            print(f"        {k:34s} {sd[k]}")
    rr, dr = sd.get("Innodb_buffer_pool_read_requests", 0), sd.get("Innodb_buffer_pool_reads", 0)
    if rr:
        print(f"        buffer_pool_hit_rate               {(1-dr/rr)*100:.6f}%")

    # 원인 — digest 실행당
    rows = digest_rows(label)
    rows = [r for r in rows if r.get("SCHEMA_NAME") == "solply_bench_db"]
    def num(r, k):
        try: return float(r[k])
        except (KeyError, ValueError): return 0.0
    rows.sort(key=lambda r: -num(r, "SUM_TIMER_WAIT"))
    print("[원인] digest 실행당 (상위 8, 시간 내림차순):")
    print(f"        {'문장(앞 70자)':72s} {'호출':>8s} {'행/실행':>9s} {'ms/실행':>9s}")
    for r in rows[:8]:
        cnt = num(r, "COUNT_STAR") or 1
        txt = (r.get("DIGEST_TEXT") or "")[:70].replace("\\n", " ")
        print(f"        {txt:72s} {cnt:8.0f} {num(r,'SUM_ROWS_EXAMINED')/cnt:9.1f} {num(r,'SUM_TIMER_WAIT')/cnt/1e9:9.3f}")
    print()


if __name__ == "__main__":
    for lb in sys.argv[1:]:
        report(lb)
