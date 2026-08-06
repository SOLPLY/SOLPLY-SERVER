#!/usr/bin/env python3
"""jfr print --json 결과를 Artillery 실제 도착 창에 맞춰 요약한다."""

import argparse
import collections
import datetime as dt
import json
import re
from pathlib import Path


def instant(value: str) -> float:
    return dt.datetime.fromisoformat(value).timestamp()


def seconds(value: str) -> float:
    match = re.fullmatch(r"PT([0-9.]+)S", value)
    return float(match.group(1)) if match else 0.0


def class_name(value: dict) -> str:
    package = value.get("package") or {}
    prefix = package.get("name", "").replace("/", ".")
    raw_name = value.get("name", "?")
    # JFR JSON은 어떤 class type에는 단순명, 어떤 type에는 전체 내부명을 넣는다.
    if "/" in raw_name:
        return raw_name.replace("/", ".")
    name = raw_name
    return f"{prefix}.{name}" if prefix else name


def method_name(frame: dict) -> str:
    method = frame.get("method") or {}
    return f"{class_name(method.get('type') or {})}.{method.get('name', '?')}"


def frames(event: dict) -> list[str]:
    trace = event["values"].get("stackTrace") or {}
    return [method_name(frame) for frame in trace.get("frames", [])]


def within(event: dict, begin: float, end: float) -> bool:
    timestamp = event.get("values", {}).get("startTime")
    return bool(timestamp) and begin <= instant(timestamp) < end


def top_lines(counter: collections.Counter, total: int, limit: int = 12) -> list[str]:
    if not counter:
        return ["  (none)"]
    return [f"  {count:5d}  {count / total * 100:6.2f}%  {name}" for name, count in counter.most_common(limit)]


def summarize(events: list[dict], begin: float, end: float, label: str) -> str:
    java = [e for e in events if e["type"] == "jdk.ExecutionSample" and within(e, begin, end)]
    native = [e for e in events if e["type"] == "jdk.NativeMethodSample" and within(e, begin, end)]
    # ExecutionSample은 runnable Java 코드의 CPU 표본이다. NativeMethodSample에는 EPoll.wait처럼
    # 잠든 I/O 스레드가 섞이므로 CPU 비율의 분모/분자로 합치지 않는다.
    samples = java
    leaf = collections.Counter()
    app_anchor = collections.Counter()
    threads = collections.Counter()
    groups = collections.Counter()
    group_prefixes = {
        "application": "org.sopt.solply_server.",
        "mysql-driver": "com.mysql.cj.",
        "hibernate": "org.hibernate.",
        "spring-data": "org.springframework.data.",
        "spring-security": "org.springframework.security.",
        "jwt": "io.jsonwebtoken.",
        "jackson": "com.fasterxml.jackson.",
        "tomcat": "org.apache.tomcat.",
    }

    for event in samples:
        stack = frames(event)
        if stack:
            leaf[stack[0]] += 1
        thread = event["values"].get("sampledThread") or {}
        threads[thread.get("javaName") or thread.get("osName") or "?"] += 1
        for method in stack:
            if method.startswith("org.sopt.solply_server."):
                app_anchor[method] += 1
                break
        for group, prefix in group_prefixes.items():
            if any(method.startswith(prefix) for method in stack):
                groups[group] += 1

    gc = [e for e in events if e["type"] == "jdk.GarbageCollection" and within(e, begin, end)]
    gc_pauses = [seconds(e["values"].get("sumOfPauses", "PT0S")) for e in gc]

    locks = [e for e in events if e["type"] == "jdk.JavaMonitorEnter" and within(e, begin, end)]
    lock_time = sum(seconds(e["values"].get("duration", "PT0S")) for e in locks)
    lock_classes = collections.Counter(class_name(e["values"].get("monitorClass") or {}) for e in locks)

    allocations = [e for e in events if e["type"] == "jdk.ThreadAllocationStatistics" and within(e, begin, end)]
    by_thread: dict[int, list[int]] = collections.defaultdict(list)
    for event in allocations:
        values = event["values"]
        thread_id = (values.get("thread") or {}).get("javaThreadId", -1)
        by_thread[thread_id].append(values.get("allocated", 0))
    allocated = sum(max(values) - min(values) for values in by_thread.values() if len(values) >= 2)

    cpu = [e["values"] for e in events if e["type"] == "jdk.CPULoad" and within(e, begin, end)]
    jvm_load = [value.get("jvmUser", 0.0) + value.get("jvmSystem", 0.0) for value in cpu]

    out = [
        f"## {label} ({end - begin:.1f}s arrival window)",
        f"samples java_cpu={len(java)} native_context={len(native)}",
        f"jvm_cpu_fraction mean={sum(jvm_load) / len(jvm_load):.4f} max={max(jvm_load):.4f}" if jvm_load else "jvm_cpu_fraction n/a",
        f"allocation_delta={allocated / 1024 / 1024:.1f} MiB ({allocated / (end - begin) / 1024 / 1024:.1f} MiB/s)",
        f"gc count={len(gc)} pause_sum={sum(gc_pauses) * 1000:.1f} ms pause_max={(max(gc_pauses) if gc_pauses else 0) * 1000:.1f} ms",
        f"monitor_enter>=20ms count={len(locks)} wait_sum={lock_time * 1000:.1f} ms",
        "top leaf methods:",
        *top_lines(leaf, len(samples)),
        "top application anchors (deepest application frame):",
        *top_lines(app_anchor, len(samples)),
        "inclusive stack groups (overlapping):",
        *top_lines(groups, len(samples)),
        "top sampled threads:",
        *top_lines(threads, len(samples)),
        "top contended monitor classes:",
        *top_lines(lock_classes, len(locks) if locks else 1, 8),
    ]
    return "\n".join(out)


def align_to_cpu(events: list[dict], reported_begin: float) -> float:
    """Artillery counter clock precedes actual server work by about 8s in this CLI version.

    Pick the first CPULoad sample above 10% of the 11-vCPU Docker VM (~1.1 cores), requiring
    three consecutive high samples. This only aligns the profiling window; it does not classify
    an outcome or choose a hotspot.
    """
    loads = []
    for event in events:
        if event["type"] != "jdk.CPULoad":
            continue
        values = event["values"]
        timestamp = instant(values["startTime"])
        if reported_begin <= timestamp <= reported_begin + 15:
            loads.append((timestamp, values.get("jvmUser", 0.0) + values.get("jvmSystem", 0.0)))
    for index in range(len(loads) - 2):
        if all(load > 0.10 for _, load in loads[index:index + 3]):
            return loads[index][0]
    return reported_begin


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("events_json", type=Path)
    parser.add_argument("reports_dir", type=Path)
    parser.add_argument("label")
    args = parser.parse_args()

    with args.events_json.open() as stream:
        events = json.load(stream)["recording"]["events"]

    sections = []
    for stage, duration in (("load-300", 20), ("load-360", 20)):
        report = args.reports_dir / f"{args.label}.{stage}.json"
        with report.open() as stream:
            aggregate = json.load(stream)["aggregate"]
        reported_begin = aggregate["firstCounterAt"] / 1000
        begin = align_to_cpu(events, reported_begin)
        section = summarize(events, begin, begin + duration, stage)
        sections.append(
            f"reported_first={dt.datetime.fromtimestamp(reported_begin).isoformat()} "
            f"profile_begin={dt.datetime.fromtimestamp(begin).isoformat()} "
            f"offset={begin - reported_begin:.3f}s\n{section}"
        )
    print("\n\n".join(sections))


if __name__ == "__main__":
    main()
