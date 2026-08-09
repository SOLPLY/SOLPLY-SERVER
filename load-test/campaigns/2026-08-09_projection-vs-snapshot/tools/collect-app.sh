#!/bin/sh
# 앱 컨테이너 안에서 1초마다 도는 수집기. 호스트에서 `docker exec -i ... sh -s <N> < 이파일`로 넣는다.
# docker exec를 매 초 새로 띄우지 않는 이유: exec 생성 비용 자체가 계측 대상 CPU에 섞인다.
#
# cgroup 값은 전부 **누계**다. 구간 값은 분석 스크립트가 연속 두 샘플의 차분으로 만든다
# (라운드 시작/종료 누계만으로는 계단 구간별 값을 만들 수 없다 — 이 캠페인의 요구사항).
# /proc/{loadavg,stat,pressure}는 컨테이너가 아니라 **Docker VM 전체**를 본다. 앱만의 값이
# 아니므로 run queue·context switch는 "호스트(VM) 경합 프록시"로만 읽는다.
N="${1:-180}"
i=0
printf 'ts\tusage_usec\tuser_usec\tsystem_usec\tnr_periods\tnr_throttled\tthrottled_usec\tmem_current\tpsi_some_us\tpsi_full_us\tvm_load1\tvm_runnable\tvm_ctxt\tvm_procs_running\tvm_procs_blocked\tvm_psi_some_us\n'
while [ "$i" -lt "$N" ]; do
  TS=$(date +%s.%N); case "$TS" in *N*) TS=$(date +%s);; esac
  awk -v ts="$TS" '
    FILENAME ~ /cpu.stat$/     { c[$1] = $2 }
    FILENAME ~ /memory.current$/ { mem = $1 }
    FILENAME ~ /cgroup\/cpu.pressure$/ { for (f = 1; f <= NF; f++) if ($f ~ /^total=/) { split($f, a, "="); p[$1] = a[2] } }
    FILENAME ~ /proc\/pressure/        { for (f = 1; f <= NF; f++) if ($f ~ /^total=/ && $1 == "some") { split($f, a, "="); vmp = a[2] } }
    FILENAME ~ /loadavg$/      { load1 = $1; runq = $4 }
    FILENAME ~ /proc\/stat$/   { if ($1 == "ctxt") ctxt = $2; if ($1 == "procs_running") pr = $2; if ($1 == "procs_blocked") pb = $2 }
    END {
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts,
        c["usage_usec"], c["user_usec"], c["system_usec"], c["nr_periods"], c["nr_throttled"], c["throttled_usec"],
        mem, p["some"], p["full"], load1, runq, ctxt, pr, pb, vmp
    }' /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.current /sys/fs/cgroup/cpu.pressure \
       /proc/pressure/cpu /proc/loadavg /proc/stat
  i=$((i + 1))
  sleep 1
done
