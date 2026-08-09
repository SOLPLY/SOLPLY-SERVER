#!/bin/sh
# 앱 컨테이너 안에서 1초마다 actuator/prometheus를 긁어 필요한 값만 남긴다.
# 2026-08-07_place-skeleton-cache/tools/collect-jvm.sh를 계승하되 **세 계열을 추가**했다 —
# 이 캠페인의 보조 지표(사전 등록 §5.1)가 하이드레이션 몫과 커넥션 점유 몫의 방향이라서다.
#   jvm_gc_memory_allocated_bytes_total   요청당 힙 할당 (엔티티 하이드레이션의 직접 프록시)
#   hikaricp_connections_acquire_seconds  커넥션 획득 대기 (count·sum → 구간 평균 대기)
#   hikaricp_connections_usage_seconds    커넥션 점유 시간 (count·sum → 구간 평균 점유)
# 셋 다 누계이므로 구간 값은 분석에서 차분으로 만든다.
#
# 계측 자체가 앱의 tomcat 스레드를 초당 1개 점유한다(최대 부하 시 약 650 req/s 중 1). 무시할
# 수준이지만 원자료에 남긴다. nginx는 /actuator를 403으로 막으므로 컨테이너 안에서 localhost로 친다.
N="${1:-180}"
i=0
printf 'ts\tthreads_live\tthreads_daemon\tthreads_peak\tgc_pause_count\tgc_pause_sum_s\tgc_pause_max_s\theap_used\thikari_active\thikari_pending\thikari_idle\tprocess_cpu_usage\talloc_bytes\thik_acq_count\thik_acq_sum_s\thik_use_count\thik_use_sum_s\n'
while [ "$i" -lt "$N" ]; do
  TS=$(date +%s.%N); case "$TS" in *N*) TS=$(date +%s);; esac
  wget -qO- http://localhost:8082/actuator/prometheus 2>/dev/null | awk -v ts="$TS" '
    /^#/ { next }
    { n = $1; sub(/\{.*/, "", n); v = $NF + 0 }
    n == "jvm_threads_live_threads"    { live = v }
    n == "jvm_threads_daemon_threads"  { daemon = v }
    n == "jvm_threads_peak_threads"    { peak = v }
    n == "jvm_gc_pause_seconds_count"  { gcc += v }
    n == "jvm_gc_pause_seconds_sum"    { gcs += v }
    n == "jvm_gc_pause_seconds_max"    { if (v > gcm) gcm = v }
    n == "jvm_memory_used_bytes" && $1 ~ /area="heap"/ { heap += v }
    n == "hikaricp_connections_active"  { ha = v }
    n == "hikaricp_connections_pending" { hp = v }
    n == "hikaricp_connections_idle"    { hi = v }
    n == "process_cpu_usage"            { pcu = v }
    n == "jvm_gc_memory_allocated_bytes_total"     { alloc = v }
    n == "hikaricp_connections_acquire_seconds_count" { hac = v }
    n == "hikaricp_connections_acquire_seconds_sum"   { has = v }
    n == "hikaricp_connections_usage_seconds_count"   { huc = v }
    n == "hikaricp_connections_usage_seconds_sum"     { hus = v }
    END { printf "%s\t%s\t%s\t%s\t%s\t%.6f\t%.6f\t%s\t%s\t%s\t%s\t%.6f\t%.0f\t%.0f\t%.6f\t%.0f\t%.6f\n",
            ts, live, daemon, peak, gcc, gcs, gcm, heap, ha, hp, hi, pcu, alloc, hac, has, huc, hus }'
  i=$((i + 1))
  sleep 1
done
