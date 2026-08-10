#!/bin/sh
# MySQL 컨테이너 안에서 1초마다 도는 cgroup 수집기. 앱 수집기와 같은 형식·같은 타임라인.
# "DB는 여유로운데 앱만 포화되는가"(질문 4)를 같은 해상도로 대조하기 위해 앱과 동일 항목을 뜬다.
N="${1:-180}"
i=0
printf 'ts\tusage_usec\tuser_usec\tsystem_usec\tnr_periods\tnr_throttled\tthrottled_usec\tmem_current\tpsi_some_us\tpsi_full_us\n'
while [ "$i" -lt "$N" ]; do
  TS=$(date +%s.%N); case "$TS" in *N*) TS=$(date +%s);; esac
  awk -v ts="$TS" '
    FILENAME ~ /cpu.stat$/       { c[$1] = $2 }
    FILENAME ~ /memory.current$/ { mem = $1 }
    FILENAME ~ /cpu.pressure$/   { for (f = 1; f <= NF; f++) if ($f ~ /^total=/) { split($f, a, "="); p[$1] = a[2] } }
    END {
      printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts,
        c["usage_usec"], c["user_usec"], c["system_usec"], c["nr_periods"], c["nr_throttled"], c["throttled_usec"],
        mem, p["some"], p["full"]
    }' /sys/fs/cgroup/cpu.stat /sys/fs/cgroup/memory.current /sys/fs/cgroup/cpu.pressure
  i=$((i + 1))
  sleep 1
done
