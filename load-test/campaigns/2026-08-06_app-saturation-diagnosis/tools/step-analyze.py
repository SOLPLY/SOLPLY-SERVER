#!/usr/bin/env python3
"""계단형 라운드 하나를 계단별 표로 만든다 — 사전 등록(README §5)의 P1~P5 판정용.

Artillery 10초 버킷과 1초 샘플을 **같은 epoch 타임라인**에 올려 자른다.
1초 샘플은 전부 누계이므로 구간 값은 여기서 차분으로 만든다 — 라운드 시작/종료 누계로
계단을 추정하지 않는다.

사용: python3 tools/step-analyze.py <라벨>
"""
import json
import os
import sys

CAMP = os.path.join(os.path.dirname(__file__), "..")
R = os.path.join(CAMP, "results")
M = os.path.join(R, "metrics")

# (이름, 설정 arrivals/s, 시작 오프셋 s, 길이 s) — scenarios/saturation-step.yml의 phases와 일치
STEPS = [
    ("warmup", 60, 0, 30),
    ("S1", 120, 30, 30),
    ("S2", 180, 60, 30),
    ("S3", 240, 90, 30),
    ("S4", 300, 120, 30),
    ("S5", 360, 150, 30),
]
QUOTA_CORES = 2.0  # 앱·DB 둘 다 cpus: 2


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
    """구간 내 순간값의 (평균, 최대)."""
    vals = [fnum(r, key) for r in rows if r.get("ts") and t_lo <= float(r["ts"]) < t_hi]
    vals = [v for v in vals if v is not None]
    if not vals:
        return None, None
    return sum(vals) / len(vals), max(vals)


def runq_stat(rows, t_lo, t_hi):
    """/proc/loadavg 4번째 필드 'R/T'의 R(실행가능 태스크 수) 평균·최대."""
    vals = []
    for r in rows:
        if not r.get("ts") or not (t_lo <= float(r["ts"]) < t_hi):
            continue
        v = r.get("vm_runnable", "")
        if "/" in v:
            try:
                vals.append(int(v.split("/")[0]))
            except ValueError:
                pass
    if not vals:
        return None, None
    return sum(vals) / len(vals), max(vals)


def med(xs):
    xs = sorted(x for x in xs if x is not None)
    return xs[len(xs) // 2] if xs else None


def ratio(new, old):
    if new is None or old in (None, 0):
        return None
    return new / old


def fmt(v, spec="{:.2f}"):
    return spec.format(v) if v is not None else "NA"


def main(label):
    rep = json.load(open(f"{R}/reports/{label}.json"))
    ints = [b for b in rep.get("intermediate", []) if b.get("period")]
    ints.sort(key=lambda b: int(b["period"]))
    # 부하 개시 시각 = 첫 카운터 시각. **버킷 경계(period)가 아니다** — Artillery는 첫 버킷을
    # 프로세스 기동과 함께 열고 실제 첫 요청은 그보다 늦게(이 라운드는 9.0초) 나간다.
    # 이걸 t0으로 쓰면 계단 창이 통째로 앞으로 밀려 낮은 계단이 섞이고, 도착률이 결손난 것처럼
    # 보인다(실제 도착 총계는 계획과 정확히 일치했다).
    t_start = ints[0]["firstCounterAt"] / 1000.0
    t0 = t_start

    app = load_tsv(f"{M}/{label}.app1s.tsv")
    db = load_tsv(f"{M}/{label}.db1s.tsv")
    jvm = load_tsv(f"{M}/{label}.jvm1s.tsv")
    dbst = load_tsv(f"{M}/{label}.dbstat1s.tsv")

    print("=" * 100)
    print(f"# 계단형 라운드 {label}")
    print(f"  부하 개시(firstCounterAt) epoch {t_start:.3f}  버킷 {len(ints)}개"
          f"  (첫 버킷 경계 대비 +{t_start - int(ints[0]['period']) / 1000.0:.1f}s)")
    print(f"  1초 샘플: app {len(app)} · db {len(db)} · jvm {len(jvm)} · dbstat {len(dbst)}")
    agg = rep["aggregate"]["counters"]
    print(f"  라운드 합계: 도착 {agg.get('vusers.created',0)} · 완료 {agg.get('vusers.completed',0)}"
          f" · 실패 {agg.get('vusers.failed',0)} · HTTP 요청 {agg.get('http.requests',0)}"
          f" · 응답 {agg.get('http.responses',0)}")
    print(f"  코드 { {k.split('.')[-1]: v for k, v in agg.items() if k.startswith('http.codes.')} }"
          f"  에러 { {k: v for k, v in agg.items() if k.startswith('errors.')} }")
    print("=" * 100)

    rows = []
    for name, rate, off, dur in STEPS:
        s_lo, s_hi = t0 + off, t0 + off + dur
        # 계단에 **완전히 포함되는** 10초 버킷만 그 계단의 대표로 쓴다. 계단 경계가 버킷 경계와
        # 9초 어긋나 있어(위 참조) 걸친 버킷을 넣으면 인접 계단의 부하가 섞인다.
        bk = [b for b in ints
              if int(b["period"]) >= s_lo * 1000 and int(b["period"]) + 10_000 <= s_hi * 1000]
        if not bk:
            continue
        # 자원 1초 샘플도 **같은 대표 구간**으로 자른다 — 결과와 자원을 같은 타임라인에 올린다.
        lo = int(bk[0]["period"]) / 1000.0
        hi = (int(bk[-1]["period"]) + 10_000) / 1000.0

        created = sum(b["counters"].get("vusers.created", 0) for b in bk)
        reqs = sum(b["counters"].get("http.requests", 0) for b in bk)
        resp = sum(b["counters"].get("http.responses", 0) for b in bk)
        failed = sum(b["counters"].get("vusers.failed", 0) for b in bk)
        span = hi - lo
        p50 = med([b["summaries"].get("http.response_time", {}).get("p50") for b in bk])
        p95 = med([b["summaries"].get("http.response_time", {}).get("p95") for b in bk])
        p99 = med([b["summaries"].get("http.response_time", {}).get("p99") for b in bk])
        p50s = [b["summaries"].get("http.response_time", {}).get("p50") for b in bk]
        p95s = [b["summaries"].get("http.response_time", {}).get("p95") for b in bk]

        AK = ["usage_usec", "nr_periods", "nr_throttled", "throttled_usec", "psi_some_us",
              "psi_full_us", "vm_ctxt", "vm_psi_some_us"]
        ad, ael, an = window_delta(app, lo, hi, AK)
        DK = ["usage_usec", "nr_periods", "nr_throttled", "throttled_usec", "psi_some_us", "psi_full_us"]
        dd, del_, dn = window_delta(db, lo, hi, DK)
        JK = ["gc_pause_count", "gc_pause_sum_s"]
        jd, jel, jn = window_delta(jvm, lo, hi, JK)
        SK = ["queries", "row_lock_waits", "row_lock_time_ms", "bp_reads", "tmp_disk_tables"]
        sd, sel_, sn = window_delta(dbst, lo, hi, SK)

        app_cores = (ad["usage_usec"] / 1e6 / ael) if ad and ael else None
        app_thr_pct = (ad["nr_throttled"] / ad["nr_periods"] * 100) if ad and ad["nr_periods"] else None
        app_thr_s = (ad["throttled_usec"] / 1e6) if ad else None
        db_cores = (dd["usage_usec"] / 1e6 / del_) if dd and del_ else None
        db_thr_pct = (dd["nr_throttled"] / dd["nr_periods"] * 100) if dd and dd["nr_periods"] else None
        psi_some = (ad["psi_some_us"] / ael / 1e4) if ad and ael else None   # % of wall time
        psi_full = (ad["psi_full_us"] / ael / 1e4) if ad and ael else None
        db_psi_some = (dd["psi_some_us"] / del_ / 1e4) if dd and del_ else None
        ctxt_s = (ad["vm_ctxt"] / ael) if ad and ael else None
        gc_n = jd["gc_pause_count"] if jd else None
        gc_ms = (jd["gc_pause_sum_s"] * 1000) if jd else None
        thr_avg, thr_max = window_stat(jvm, lo, hi, "threads_live")
        hik_avg, hik_max = window_stat(jvm, lo, hi, "hikari_active")
        hikp_avg, hikp_max = window_stat(jvm, lo, hi, "hikari_pending")
        rq_avg, rq_max = runq_stat(app, lo, hi)
        pr_avg, pr_max = window_stat(app, lo, hi, "vm_procs_running")
        tc_avg, tc_max = window_stat(dbst, lo, hi, "threads_connected")
        tr_avg, tr_max = window_stat(dbst, lo, hi, "threads_running")

        rows.append(dict(
            name=name, rate=rate, buckets=len(bk),
            arr=created / span, arr_pct=created / (rate * span) * 100,
            req=reqs / span, resp=resp / span, failed=failed,
            req_per_arr=(reqs / created) if created else None,
            p50=p50, p95=p95, p99=p99, p50s=p50s, p95s=p95s,
            app_cores=app_cores, app_thr_pct=app_thr_pct, app_thr_s=app_thr_s,
            psi_some=psi_some, psi_full=psi_full, ctxt_s=ctxt_s,
            rq_avg=rq_avg, rq_max=rq_max, pr_avg=pr_avg, pr_max=pr_max,
            gc_n=gc_n, gc_ms=gc_ms, thr_avg=thr_avg, thr_max=thr_max,
            hik_avg=hik_avg, hik_max=hik_max, hikp_max=hikp_max,
            db_cores=db_cores, db_thr_pct=db_thr_pct, db_psi_some=db_psi_some,
            tc_max=tc_max, tr_avg=tr_avg, tr_max=tr_max,
            qps=(sd["queries"] / sel_) if sd and sel_ else None,
            lock_waits=sd["row_lock_waits"] if sd else None,
            lock_ms=sd["row_lock_time_ms"] if sd else None,
            bp_reads=sd["bp_reads"] if sd else None,
            tmp_disk=sd["tmp_disk_tables"] if sd else None,
            n_app=an, n_db=dn, n_jvm=jn, n_st=sn, elapsed=ael,
        ))

    hdr = ["계단", "설정도착/s", "실도착/s", "도착률%", "HTTP req/s", "req/도착",
           "p50", "p95", "p99", "앱코어", "앱thr%", "앱thr(s)",
           "PSIsome%", "ctxt/s", "runq평균", "JVMthr", "GC회", "GC(ms)",
           "DB코어", "DBthr%", "DBconn", "DBrun", "QPS", "락대기"]
    print("\n## 계단별 표 (1초 샘플 차분 · Artillery 10초 버킷 3개/계단)\n")
    print("| " + " | ".join(hdr) + " |")
    print("|" + "|".join(["---"] * len(hdr)) + "|")
    for r in rows:
        print("| " + " | ".join([
            r["name"], str(r["rate"]), fmt(r["arr"], "{:.1f}"), fmt(r["arr_pct"], "{:.1f}"),
            fmt(r["req"], "{:.1f}"), fmt(r["req_per_arr"], "{:.2f}"),
            fmt(r["p50"], "{:.1f}"), fmt(r["p95"], "{:.1f}"), fmt(r["p99"], "{:.1f}"),
            fmt(r["app_cores"], "{:.3f}"), fmt(r["app_thr_pct"], "{:.1f}"), fmt(r["app_thr_s"], "{:.2f}"),
            fmt(r["psi_some"], "{:.1f}"), fmt(r["ctxt_s"], "{:.0f}"), fmt(r["rq_avg"], "{:.1f}"),
            fmt(r["thr_avg"], "{:.1f}"), fmt(r["gc_n"], "{:.0f}"), fmt(r["gc_ms"], "{:.0f}"),
            fmt(r["db_cores"], "{:.3f}"), fmt(r["db_thr_pct"], "{:.1f}"),
            fmt(r["tc_max"], "{:.0f}"), fmt(r["tr_max"], "{:.0f}"),
            fmt(r["qps"], "{:.0f}"), fmt(r["lock_waits"], "{:.0f}"),
        ]) + " |")

    print("\n## 계단별 원자료 보조 (버킷별 백분위 · 백분위는 재집계 불가)")
    for r in rows:
        print(f"  {r['name']:7s} 버킷 p50 {r['p50s']}  p95 {r['p95s']}  "
              f"실패 {r['failed']}  샘플수 app={r['n_app']} db={r['n_db']} jvm={r['n_jvm']} st={r['n_st']} "
              f"실경과 {fmt(r['elapsed'],'{:.1f}')}s")
    print("\n  (참고) 앱 PSI full%, DB PSI some%, VM procs_running, Hikari active/pending, "
          "buffer pool reads, tmp disk tables:")
    for r in rows:
        print(f"  {r['name']:7s} PSIfull {fmt(r['psi_full'],'{:.1f}')}%  DBPSIsome {fmt(r['db_psi_some'],'{:.1f}')}%  "
              f"procs_running평균 {fmt(r['pr_avg'],'{:.1f}')}/최대 {fmt(r['pr_max'],'{:.0f}')}  "
              f"runq최대 {fmt(r['rq_max'],'{:.0f}')}  JVMthr최대 {fmt(r['thr_max'],'{:.0f}')}  "
              f"Hikari act {fmt(r['hik_avg'],'{:.1f}')}/max {fmt(r['hik_max'],'{:.0f}')} pend最大 {fmt(r['hikp_max'],'{:.0f}')}  "
              f"bp_reads {fmt(r['bp_reads'],'{:.0f}')}  tmpdisk {fmt(r['tmp_disk'],'{:.0f}')}  "
              f"락시간 {fmt(r['lock_ms'],'{:.0f}')}ms")

    # ── 사전 등록 판정 ────────────────────────────────────────────────────────
    print("\n" + "=" * 100)
    print("## 사전 등록 판정 (README §5)")
    print("=" * 100)
    ld = rows[1:]  # warmup 제외
    print("\n### 계단 간 증가율 (직전 계단 대비)")
    print("| 전이 | 도착 배수 | HTTP req/s 배수 | p50 배수 | p95 배수 | knee지수(p95배수/req배수) |")
    print("|---|---|---|---|---|---|")
    knee = []
    for i in range(1, len(ld)):
        a, b = ld[i - 1], ld[i]
        rr = ratio(b["req"], a["req"])
        ar = ratio(b["arr"], a["arr"])
        p5 = ratio(b["p50"], a["p50"])
        p9 = ratio(b["p95"], a["p95"])
        ki = (p9 / rr) if (p9 and rr) else None
        knee.append((f"{a['name']}→{b['name']}", ar, rr, p5, p9, ki))
        print(f"| {a['name']}→{b['name']} | {fmt(ar)} | {fmt(rr)} | {fmt(p5)} | {fmt(p9)} | {fmt(ki)} |")

    def verdict(ok, txt):
        return ("성립  " if ok else "불성립") + "  " + txt

    # P1: 도착 ≥1.2배인데 실제 req/s < 1.05배
    p1 = [(n, ar, rr) for n, ar, rr, _, _, _ in knee if ar and rr and ar >= 1.2 and rr < 1.05]
    print("\nP1 처리량 평탄화 (도착 ≥1.2배인데 HTTP req/s <1.05배):")
    print("   " + verdict(bool(p1), str([(n, round(ar, 2), round(rr, 2)) for n, ar, rr in p1]) if p1 else
                          "모든 전이에서 실제 req/s가 1.05배 이상 증가"))
    # P2: 절대 코어 ≥1.8 또는 throttled ≥10%
    p2 = [(r["name"], round(r["app_cores"], 3), round(r["app_thr_pct"], 1))
          for r in ld if r["app_cores"] and (r["app_cores"] >= 1.8 or (r["app_thr_pct"] or 0) >= 10)]
    print("P2 CPU 상한 도달 (절대 코어 ≥1.8 또는 throttled period ≥10%):")
    print("   " + verdict(bool(p2), str(p2) if p2 else "해당 계단 없음"))
    # P3: knee 지수 ≥3
    p3 = [(n, round(ki, 2)) for n, _, _, _, _, ki in knee if ki and ki >= 3]
    print("P3 knee (p95 증가율 ÷ req/s 증가율 ≥ 3):")
    print("   " + verdict(bool(p3), str(p3) if p3 else "모든 전이에서 3배 미만"))
    # P4: knee 계단에서 PSI·ctxt·threads 중 2개 이상이 직전 대비 ≥1.3배
    print("P4 큐·스위치·GC 동반 (knee 계단에서 PSIsome/ctxt/JVMthreads 중 2개 이상 ≥1.3배):")
    if p3:
        for n, _ in p3:
            b_name = n.split("→")[1]
            i = [k for k, r in enumerate(ld) if r["name"] == b_name][0]
            a, b = ld[i - 1], ld[i]
            f_psi, f_ctxt, f_thr = ratio(b["psi_some"], a["psi_some"]), ratio(b["ctxt_s"], a["ctxt_s"]), ratio(b["thr_avg"], a["thr_avg"])
            f_gc = ratio(b["gc_ms"], a["gc_ms"])
            cnt = sum(1 for f in (f_psi, f_ctxt, f_thr) if f and f >= 1.3)
            print(f"   {n}: PSIsome ×{fmt(f_psi)} · ctxt ×{fmt(f_ctxt)} · JVMthreads ×{fmt(f_thr)} "
                  f"· GC시간 ×{fmt(f_gc)} → {cnt}개 충족 → {'성립' if cnt >= 2 else '불성립'}")
    else:
        print("   knee 계단이 없어 판정 불가")
    # P5: DB 여유
    print("P5 앱만 포화 (해당 계단 DB 절대 코어 <1.0 · DB throttled <5% · 락대기 0):")
    for r in ld:
        ok = (r["db_cores"] is not None and r["db_cores"] < 1.0
              and (r["db_thr_pct"] or 0) < 5 and (r["lock_waits"] or 0) == 0)
        print(f"   {r['name']}: DB코어 {fmt(r['db_cores'],'{:.3f}')} · DBthr {fmt(r['db_thr_pct'],'{:.1f}')}% "
              f"· 락대기 {fmt(r['lock_waits'],'{:.0f}')} → {'여유' if ok else '여유 아님'}")
    print()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "sat-step-1")
