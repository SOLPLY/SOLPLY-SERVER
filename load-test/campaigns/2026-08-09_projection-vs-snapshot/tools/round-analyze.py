#!/usr/bin/env python3
"""고정 부하 라운드를 phase별 표로 만든다 — 사전 등록 §5.1의 1급 지표와 §6의 유효성 판정용.

직전 캠페인 tools/step-analyze.py의 계승판. 바뀐 것 셋:

  ① 계단(STEPS)이 phase(w1~w5-hold/measure)로 바뀌었다. 판정에 쓰는 것은
     **measure 하나**이고 앞 셋은 폐기한다 (사전 등록 §7: "라운드 내 워밍업 구간(폐기) → 측정 구간").
  ② **요청당 앱 CPU ms**를 1급 지표로 뽑는다 — measure 구간의 cgroup usage_usec 차분을 그
     구간의 HTTP 요청 수로 나눈다. 라운드 전체 누계로도 함께 내서 워밍업 포함 오차를 보인다.
  ③ 보조 지표 셋을 추가했다: 요청당 힙 할당(jvm_gc_memory_allocated), 커넥션 획득 대기·점유
     (hikaricp_connections_{acquire,usage}_seconds). 셋 다 누계라 구간 차분으로 만든다.

digest는 라운드 단위로만 창을 만들 수 있다(truncate가 라운드 시작, dump가 라운드 끝). 그래서
골격 계열 문장의 "요청당 실행수"는 **라운드 전체 기준**이고, 그 사실을 표에 명시한다 —
요청 믹스가 phase에 걸쳐 동일(weight 고정)하므로 비율 자체는 measure 구간과 같다.

사용: python3 tools/round-analyze.py <라벨> [<라벨> ...]
      python3 tools/round-analyze.py --compare <라벨> [<라벨> ...]
"""
import json
import os
import statistics
import sys

CAMP = os.path.join(os.path.dirname(__file__), "..")
R = os.path.join(CAMP, "results")
M = os.path.join(R, "metrics")
D = os.path.join(R, "digests")

# (이름, 시작 오프셋 s, 길이 s) — scenarios/mode-fixed-{300,420}.yml의 phases와 일치해야 한다
PHASES = [("w1", 0, 30), ("w2", 30, 30), ("w3", 60, 30), ("w4", 90, 30),
          ("w5-hold", 120, 30), ("measure", 150, 60)]
QUOTA_CORES = 2.0  # 앱·DB 둘 다 cpus: 2

# 골격 계열 문장 지문 — bench/verify-switch.sh와 **같은 규칙**이다. alias로 가른다.
SKELETON_SIGS = [
    ("ent_places", lambda t: "FROM `places` `p1_0`" in t),
    ("ent_images", lambda t: "FROM `place_images` `pii1_0`" in t),
    ("prj_places", lambda t: "`m` . `tag_active`" in t and "`p` . `id` IN (" in t),
    ("prj_images", lambda t: "FROM `place_images` `pi`" in t and "IN (" in t),
    ("bld_places", lambda t: "`m` . `tag_active`" in t and "`p` . `id` IN (" not in t),
    ("bld_images", lambda t: "FROM `place_images` `pi`" in t and "IN (" not in t),
]


def load_tsv(path):
    if not os.path.exists(path):
        return []
    lines = open(path).read().splitlines()
    if len(lines) < 2:
        return []
    hdr = lines[0].split("\t")
    out = []
    for ln in lines[1:]:
        f = ln.split("\t")
        if len(f) != len(hdr):
            continue
        out.append(dict(zip(hdr, f)))
    return out


def fnum(row, key):
    try:
        return float(row[key])
    except (KeyError, ValueError, TypeError):
        return None


def window_delta(rows, t_lo, t_hi, keys):
    """[t_lo, t_hi) 구간의 누계 차분과 실경과시간. 구간 양 끝 샘플을 쓴다."""
    sel = [r for r in rows if r.get("ts") and t_lo <= float(r["ts"]) < t_hi]
    if len(sel) < 2:
        return None, 0.0, 0
    a, b = sel[0], sel[-1]
    elapsed = float(b["ts"]) - float(a["ts"])
    d = {}
    for k in keys:
        va, vb = fnum(a, k), fnum(b, k)
        d[k] = (vb - va) if (va is not None and vb is not None) else None
    return d, elapsed, len(sel)


def window_stat(rows, t_lo, t_hi, key):
    vals = [fnum(r, key) for r in rows if r.get("ts") and t_lo <= float(r["ts"]) < t_hi]
    vals = [v for v in vals if v is not None]
    if not vals:
        return None, None
    return sum(vals) / len(vals), max(vals)


def med(xs):
    xs = sorted(x for x in xs if x is not None)
    return xs[len(xs) // 2] if xs else None


def fmt(v, spec="{:.2f}"):
    return spec.format(v) if v is not None else "NA"


def cpustat_pair(label):
    """라운드 전/후 cgroup 스냅샷에서 앱 usage_usec·nr_periods·nr_throttled를 꺼낸다."""
    out = {}
    for when in ("start", "end"):
        path = f"{M}/{label}.cpustat.{when}"
        if not os.path.exists(path):
            return None
        cur, vals = None, {}
        for ln in open(path):
            ln = ln.strip()
            if ln.startswith("== "):
                cur = ln[3:]
            elif cur == "solply-bench-app":
                for k in ("usage_usec", "nr_periods", "nr_throttled", "throttled_usec"):
                    if ln.startswith(k + " "):
                        vals[k] = float(ln.split()[1])
                if ln.startswith("restart_count:"):
                    vals["restart_count"] = float(ln.split()[1])
        out[when] = vals
    return out


def digest_counts(label):
    path = f"{D}/{label}.digest.tsv"
    if not os.path.exists(path):
        return None
    counts = {name: 0 for name, _ in SKELETON_SIGS}
    total = 0
    lines = open(path).read().splitlines()
    for ln in lines[1:]:
        f = ln.split("\t")
        if len(f) < 4:
            continue
        schema, text = f[0], f[2]
        try:
            n = int(f[3])
        except ValueError:
            continue
        if schema != "solply_bench_db":
            continue
        total += n
        for name, pred in SKELETON_SIGS:
            if pred(text):
                counts[name] += n
    counts["_total_statements"] = total
    return counts


def analyze(label):
    rep_path = f"{R}/reports/{label}.json"
    if not os.path.exists(rep_path):
        return None
    rep = json.load(open(rep_path))
    ints = [b for b in rep.get("intermediate", []) if b.get("period")]
    ints.sort(key=lambda b: int(b["period"]))
    if not ints:
        return None
    t0 = ints[0]["firstCounterAt"] / 1000.0

    app = load_tsv(f"{M}/{label}.app1s.tsv")
    db = load_tsv(f"{M}/{label}.db1s.tsv")
    jvm = load_tsv(f"{M}/{label}.jvm1s.tsv")
    dbst = load_tsv(f"{M}/{label}.dbstat1s.tsv")

    res = {"label": label, "t0": t0, "buckets": len(ints), "phases": {}}
    agg = rep["aggregate"]["counters"]
    res["agg"] = {
        "created": agg.get("vusers.created", 0),
        "completed": agg.get("vusers.completed", 0),
        "failed": agg.get("vusers.failed", 0),
        "requests": agg.get("http.requests", 0),
        "responses": agg.get("http.responses", 0),
        "codes": {k.split(".")[-1]: v for k, v in agg.items() if k.startswith("http.codes.")},
        "errors": {k[len("errors."):]: v for k, v in agg.items() if k.startswith("errors.")},
    }
    res["digest"] = digest_counts(label)
    res["cpustat"] = cpustat_pair(label)

    for name, off, dur in PHASES:
        s_lo, s_hi = t0 + off, t0 + off + dur
        bk = [b for b in ints
              if int(b["period"]) >= s_lo * 1000 and int(b["period"]) + 10_000 <= s_hi * 1000]
        if not bk:
            res["phases"][name] = None
            continue
        lo = int(bk[0]["period"]) / 1000.0
        hi = (int(bk[-1]["period"]) + 10_000) / 1000.0
        span = hi - lo

        created = sum(b["counters"].get("vusers.created", 0) for b in bk)
        reqs = sum(b["counters"].get("http.requests", 0) for b in bk)
        resp = sum(b["counters"].get("http.responses", 0) for b in bk)
        failed = sum(b["counters"].get("vusers.failed", 0) for b in bk)
        p50 = med([b["summaries"].get("http.response_time", {}).get("p50") for b in bk])
        p95 = med([b["summaries"].get("http.response_time", {}).get("p95") for b in bk])
        p50s = [b["summaries"].get("http.response_time", {}).get("p50") for b in bk]

        ad, ael, an = window_delta(app, lo, hi, [
            "usage_usec", "nr_periods", "nr_throttled", "throttled_usec", "psi_some_us", "vm_ctxt"])
        dd, del_, dn = window_delta(db, lo, hi, [
            "usage_usec", "nr_periods", "nr_throttled", "throttled_usec"])
        jd, jel, jn = window_delta(jvm, lo, hi, [
            "gc_pause_count", "gc_pause_sum_s", "alloc_bytes",
            "hik_acq_count", "hik_acq_sum_s", "hik_use_count", "hik_use_sum_s"])
        sd, sel_, sn = window_delta(dbst, lo, hi, ["queries", "row_lock_waits", "bp_reads", "tmp_disk_tables"])

        ph = {
            "buckets": len(bk), "span": span, "lo": lo, "hi": hi,
            "arr_s": created / span, "req_s": reqs / span, "resp_s": resp / span,
            "requests": reqs, "responses": resp, "created": created, "failed": failed,
            "p50": p50, "p95": p95, "p50s": p50s,
            "elapsed": ael, "n_app": an, "n_jvm": jn, "n_db": dn, "n_st": sn,
        }
        if ad and ael:
            ph["app_cores"] = ad["usage_usec"] / 1e6 / ael
            ph["app_cpu_ms_per_req"] = (ad["usage_usec"] / 1000.0 / reqs) if reqs else None
            ph["app_thr_pct"] = (ad["nr_throttled"] / ad["nr_periods"] * 100) if ad["nr_periods"] else None
            ph["app_thr_s"] = ad["throttled_usec"] / 1e6
            ph["psi_some_pct"] = ad["psi_some_us"] / ael / 1e4
        if dd and del_:
            ph["db_cores"] = dd["usage_usec"] / 1e6 / del_
            ph["db_cpu_ms_per_req"] = (dd["usage_usec"] / 1000.0 / reqs) if reqs else None
            ph["db_thr_pct"] = (dd["nr_throttled"] / dd["nr_periods"] * 100) if dd["nr_periods"] else None
        if jd:
            ph["alloc_kb_per_req"] = (jd["alloc_bytes"] / 1024.0 / reqs) if reqs and jd["alloc_bytes"] is not None else None
            ph["gc_n"] = jd["gc_pause_count"]
            ph["gc_ms"] = jd["gc_pause_sum_s"] * 1000 if jd["gc_pause_sum_s"] is not None else None
            if jd["hik_acq_count"]:
                ph["hik_acq_us"] = jd["hik_acq_sum_s"] / jd["hik_acq_count"] * 1e6
                ph["hik_acq_per_req"] = jd["hik_acq_count"] / reqs if reqs else None
            if jd["hik_use_count"]:
                ph["hik_use_ms"] = jd["hik_use_sum_s"] / jd["hik_use_count"] * 1e3
                ph["hik_use_per_req"] = jd["hik_use_count"] / reqs if reqs else None
        if sd and sel_:
            ph["stmt_per_req"] = (sd["queries"] / reqs) if reqs else None
            ph["qps"] = sd["queries"] / sel_
            ph["lock_waits"] = sd["row_lock_waits"]
            ph["bp_reads"] = sd["bp_reads"]
            ph["tmp_disk"] = sd["tmp_disk_tables"]
        ph["heap_avg"], ph["heap_max"] = window_stat(jvm, lo, hi, "heap_used")
        ph["hik_act_avg"], ph["hik_act_max"] = window_stat(jvm, lo, hi, "hikari_active")
        _, ph["hik_pend_max"] = window_stat(jvm, lo, hi, "hikari_pending")
        ph["thr_avg"], ph["thr_max"] = window_stat(jvm, lo, hi, "threads_live")
        res["phases"][name] = ph

    # 라운드 전체(워밍업 포함) 요청당 앱 CPU — measure 구간 값과의 차이가 워밍업 오차다
    cs = res["cpustat"]
    if cs and res["agg"]["requests"]:
        du = cs["end"].get("usage_usec", 0) - cs["start"].get("usage_usec", 0)
        res["round_app_cpu_ms_per_req"] = du / 1000.0 / res["agg"]["requests"]
        np_ = cs["end"].get("nr_periods", 0) - cs["start"].get("nr_periods", 0)
        nt_ = cs["end"].get("nr_throttled", 0) - cs["start"].get("nr_throttled", 0)
        res["round_app_thr_pct"] = (nt_ / np_ * 100) if np_ else None
        res["restart_delta"] = cs["end"].get("restart_count", 0) - cs["start"].get("restart_count", 0)
    return res


def print_one(res):
    label = res["label"]
    a = res["agg"]
    print("=" * 104)
    print(f"# 라운드 {label}   부하 개시 epoch {res['t0']:.3f}   버킷 {res['buckets']}개")
    print(f"  라운드 합계: 도착 {a['created']} · 완료 {a['completed']} · 실패 {a['failed']}"
          f" · HTTP 요청 {a['requests']} · 응답 {a['responses']}")
    print(f"  코드 {a['codes']}   에러 {a['errors']}")
    if "round_app_cpu_ms_per_req" in res:
        print(f"  라운드 전체(워밍업 포함) 요청당 앱 CPU {res['round_app_cpu_ms_per_req']:.3f}ms"
              f" · 스로틀 {fmt(res.get('round_app_thr_pct'), '{:.1f}')}%"
              f" · 컨테이너 재시작 {res.get('restart_delta')}")
    dg = res["digest"]
    if dg:
        n = a["requests"] or 1
        print("  digest(라운드 전체) 골격 계열 실행수 / 요청당:")
        for k, _ in SKELETON_SIGS:
            print(f"    {k:11s} {dg[k]:>9,d}   {dg[k]/n:6.3f}/req")
        print(f"    {'문장 총계':11s} {dg['_total_statements']:>9,d}   {dg['_total_statements']/n:6.3f}/req")
    print("-" * 104)
    hdr = ["phase", "버킷", "도착/s", "req/s", "실패", "p50", "p95", "앱코어", "앱thr%",
           "요청당앱CPUms", "요청당DBCPUms", "요청당stmt", "요청당힙KB",
           "커넥획득us", "커넥점유ms", "DB코어", "GC(ms)", "힙평균MB"]
    print("| " + " | ".join(hdr) + " |")
    print("|" + "|".join(["---"] * len(hdr)) + "|")
    for name, _, _ in PHASES:
        p = res["phases"].get(name)
        if not p:
            print(f"| {name} | (버킷 없음) |")
            continue
        print("| " + " | ".join([
            name, str(p["buckets"]), fmt(p["arr_s"], "{:.1f}"), fmt(p["req_s"], "{:.1f}"),
            str(p["failed"]), fmt(p["p50"], "{:.1f}"), fmt(p["p95"], "{:.1f}"),
            fmt(p.get("app_cores"), "{:.3f}"), fmt(p.get("app_thr_pct"), "{:.1f}"),
            fmt(p.get("app_cpu_ms_per_req"), "{:.3f}"), fmt(p.get("db_cpu_ms_per_req"), "{:.3f}"),
            fmt(p.get("stmt_per_req"), "{:.2f}"), fmt(p.get("alloc_kb_per_req"), "{:.1f}"),
            fmt(p.get("hik_acq_us"), "{:.1f}"), fmt(p.get("hik_use_ms"), "{:.2f}"),
            fmt(p.get("db_cores"), "{:.3f}"), fmt(p.get("gc_ms"), "{:.0f}"),
            fmt((p["heap_avg"] / 1048576) if p.get("heap_avg") else None, "{:.0f}"),
        ]) + " |")
    m = res["phases"].get("measure")
    if m:
        print(f"  measure 원자료: 버킷 p50 {m['p50s']} · 요청 {m['requests']} · 실경과 "
              f"{fmt(m['elapsed'],'{:.1f}')}s · 샘플 app={m['n_app']} jvm={m['n_jvm']} db={m['n_db']} st={m['n_st']}")
        print(f"  measure 보조: PSIsome {fmt(m.get('psi_some_pct'),'{:.1f}')}% · Hikari act평균 "
              f"{fmt(m.get('hik_act_avg'),'{:.1f}')}/pend최대 {fmt(m.get('hik_pend_max'),'{:.0f}')} · "
              f"획득/req {fmt(m.get('hik_acq_per_req'),'{:.2f}')} · 점유/req {fmt(m.get('hik_use_per_req'),'{:.2f}')} · "
              f"JVMthr {fmt(m.get('thr_avg'),'{:.0f}')} · 락대기 {fmt(m.get('lock_waits'),'{:.0f}')} · "
              f"bp_reads {fmt(m.get('bp_reads'),'{:.0f}')} · tmpdisk {fmt(m.get('tmp_disk'),'{:.0f}')} · "
              f"DBthr {fmt(m.get('db_thr_pct'),'{:.1f}')}%")
    print()


def print_compare(results, target_rate=300):
    print("=" * 104)
    print("## measure 구간 비교 (사전 등록 §5.1 1급 지표)")
    print("=" * 104)
    hdr = ["라벨", "도착/s", "도착률%", "req/s", "실패", "p50", "앱코어", "앱thr%",
           "요청당앱CPUms", "요청당stmt", "요청당힙KB", "커넥획득us", "커넥점유ms", "요청당DBCPUms"]
    print("| " + " | ".join(hdr) + " |")
    print("|" + "|".join(["---"] * len(hdr)) + "|")
    for res in results:
        p = res["phases"].get("measure")
        if not p:
            print(f"| {res['label']} | (measure 버킷 없음) |")
            continue
        print("| " + " | ".join([
            res["label"], fmt(p["arr_s"], "{:.1f}"),
            fmt(p["arr_s"] / target_rate * 100, "{:.1f}"),
            fmt(p["req_s"], "{:.1f}"), str(p["failed"]), fmt(p["p50"], "{:.1f}"),
            fmt(p.get("app_cores"), "{:.3f}"), fmt(p.get("app_thr_pct"), "{:.1f}"),
            fmt(p.get("app_cpu_ms_per_req"), "{:.3f}"), fmt(p.get("stmt_per_req"), "{:.2f}"),
            fmt(p.get("alloc_kb_per_req"), "{:.1f}"), fmt(p.get("hik_acq_us"), "{:.1f}"),
            fmt(p.get("hik_use_ms"), "{:.2f}"), fmt(p.get("db_cpu_ms_per_req"), "{:.3f}"),
        ]) + " |")

    print("\n## 모드별 중앙값 (라벨 접두 e-/p-/s- 로 묶는다)")
    groups = {}
    for res in results:
        mode = {"e": "entity", "p": "projection", "s": "snapshot"}.get(res["label"][0], "?")
        groups.setdefault(mode, []).append(res)
    keys = [("app_cpu_ms_per_req", "요청당 앱 CPU ms", "{:.3f}"),
            ("app_cores", "앱 코어", "{:.3f}"),
            ("stmt_per_req", "요청당 statements", "{:.2f}"),
            ("alloc_kb_per_req", "요청당 힙 KB", "{:.1f}"),
            ("hik_acq_us", "커넥션 획득 us", "{:.1f}"),
            ("hik_use_ms", "커넥션 점유 ms", "{:.2f}"),
            ("db_cpu_ms_per_req", "요청당 DB CPU ms", "{:.3f}"),
            ("app_thr_pct", "앱 스로틀 %", "{:.1f}"),
            ("p50", "p50 ms", "{:.1f}")]
    print("| 지표 | " + " | ".join(f"{m} (라운드별 → 중앙값)" for m in ("entity", "projection", "snapshot")) + " |")
    print("|" + "|".join(["---"] * 4) + "|")
    meds = {}
    for k, kname, spec in keys:
        cells = []
        for mode in ("entity", "projection", "snapshot"):
            vals = [r["phases"]["measure"].get(k) for r in groups.get(mode, [])
                    if r["phases"].get("measure")]
            vals = [v for v in vals if v is not None]
            if not vals:
                cells.append("NA")
                continue
            mv = statistics.median(vals)
            meds.setdefault(k, {})[mode] = (vals, mv)
            cells.append(", ".join(spec.format(v) for v in vals) + " → **" + spec.format(mv) + "**")
        print(f"| {kname} | " + " | ".join(cells) + " |")

    print("\n## 간극 vs 라운드 간 산포 (사전 등록 §5.1: 간극 < 산포면 '구분 불가')")
    for k, kname, spec in keys:
        g = meds.get(k, {})
        if not all(m in g for m in ("entity", "projection", "snapshot")):
            continue
        def spread(mode):
            vals, mv = g[mode]
            if len(vals) < 2 or mv == 0:
                return None
            return (max(vals) - min(vals)) / mv * 100
        e, p, s = g["entity"][1], g["projection"][1], g["snapshot"][1]
        sp = [x for x in (spread("entity"), spread("projection"), spread("snapshot")) if x is not None]
        max_sp = max(sp) if sp else None
        def gap(a, b):
            return (a - b), ((a - b) / a * 100 if a else None)
        ep, epp = gap(e, p)
        ps, psp = gap(p, s)
        print(f"  {kname}: entity {spec.format(e)} · projection {spec.format(p)} · snapshot {spec.format(s)}")
        print(f"    entity−projection = {spec.format(ep)} ({fmt(epp,'{:.1f}')}% of entity) · "
              f"projection−snapshot = {spec.format(ps)} ({fmt(psp,'{:.1f}')}% of projection) · "
              f"모드 내 라운드 산포 최대 {fmt(max_sp,'{:.1f}')}%")


def main(argv):
    compare = False
    if argv and argv[0] == "--compare":
        compare = True
        argv = argv[1:]
    results = []
    for label in argv:
        res = analyze(label)
        if res is None:
            print(f"!! {label}: 리포트 없음 — 건너뜀")
            continue
        results.append(res)
        if not compare:
            print_one(res)
    if compare and results:
        rate = 420 if any("o420" in r["label"] for r in results) else 300
        print_compare(results, rate)


if __name__ == "__main__":
    main(sys.argv[1:])
