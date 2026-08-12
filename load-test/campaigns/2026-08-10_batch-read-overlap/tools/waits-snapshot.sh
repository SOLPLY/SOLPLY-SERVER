#!/usr/bin/env bash
# InnoDB synch 대기(mutex·sxlock·cond)의 **창 diff**.
# 사용: waits-snapshot.sh start  <라벨>   (창 시작)
#       waits-snapshot.sh report <라벨>   (창 끝 → .waits.diff 생성)
#
# 전제: tools/enable-instrumentation.sql이 선행돼야 한다. 계측이 꺼져 있으면 모든 Δ가 0으로만
#       나오고, 그 0은 "경합 없음"이 아니라 "측정 안 함"이다 — 사전 등록 §5의 검출력 게이트(P 라운드)를
#       통과하기 전의 0은 판정에 쓰지 않는다.
#
# 이 표는 **서버 전역 누계**다. 창 안에서 도는 읽기 부하·배치·백그라운드 스레드의 대기가 전부 섞인다.
# 그래서 절대값이 아니라 같은 라운드의 대조 창(C)과 배치 창(B) 사이의 차이로만 읽는다.
# Δ가 전부 0이어도 정상 출력한다 — 갈래 (b)의 근거가 그 0이다.
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> load-test/

usage() {
  echo "usage: BENCH_CAMPAIGN=<캠페인 디렉터리명> $0 {start|report} <label>" >&2
  exit 1
}
[ -n "${BENCH_CAMPAIGN:-}" ] || { echo "[waits] BENCH_CAMPAIGN 환경변수 필요 (캠페인 디렉터리명)" >&2; usage; }
LABEL="${2:-}"
[ -n "$LABEL" ] || usage

OUT_DIR="campaigns/$BENCH_CAMPAIGN/results/metrics"
mkdir -p "$OUT_DIR"
MYSQL=(docker exec -i solply-bench-mysql mysql -uroot -prootpwd -N -B)

# 이 빌드(8.0.46)에는 페이지 래치 인스트루먼트(buf_block_lock)가 없다 — 블록 래치를 포함하는
# 전역 rwlock spin/os-wait 카운터(INNODB_METRICS)를 같은 diff에 합류시켜 근사한다. 타이머는 없어 0.
dump() {
  "${MYSQL[@]}" -e "
    SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT
      FROM performance_schema.events_waits_summary_global_by_event_name
     WHERE EVENT_NAME LIKE 'wait/synch/%innodb%'
     UNION ALL
    SELECT CONCAT('metrics/', NAME), \`COUNT\`, 0
      FROM information_schema.INNODB_METRICS
     WHERE NAME LIKE 'innodb_rwlock%'
     ORDER BY 1;" 2>/dev/null
}

case "${1:-}" in
  start)
    dump > "$OUT_DIR/$LABEL.waits.start"
    echo "[waits] start: $OUT_DIR/$LABEL.waits.start ($(wc -l < "$OUT_DIR/$LABEL.waits.start" | tr -d ' ')행) $(date '+%H:%M:%S')"
    ;;
  report)
    [ -f "$OUT_DIR/$LABEL.waits.start" ] || { echo "[waits] start 파일 없음: $OUT_DIR/$LABEL.waits.start" >&2; exit 2; }
    dump > "$OUT_DIR/$LABEL.waits.end"
    # SUM_TIMER_WAIT는 피코초다 (1 ms = 1e9 ps).
    {
      printf 'event_name\tdelta_count_star\tdelta_sum_timer_ms\n'
      awk -F'\t' 'NR==FNR { c[$1]=$2; t[$1]=$3; next }
        { printf "%s\t%d\t%.6f\n", $1, $2 - c[$1], ($3 - t[$1]) / 1000000000 }' \
        "$OUT_DIR/$LABEL.waits.start" "$OUT_DIR/$LABEL.waits.end" \
        | sort -t"$(printf '\t')" -k3,3gr -k2,2nr
    } > "$OUT_DIR/$LABEL.waits.diff"
    echo "[waits] diff: $OUT_DIR/$LABEL.waits.diff $(date '+%H:%M:%S')"
    awk -F'\t' 'NR>1 { c += $2; t += $3 } END { printf "[waits] 창 합계  Δcount=%d  Δtime=%.3fms\n", c, t }' \
      "$OUT_DIR/$LABEL.waits.diff"
    awk -F'\t' 'NR>1 && NR<=6 { printf "[waits]   %-52s Δcount=%-10d Δms=%.3f\n", $1, $2, $3 }' \
      "$OUT_DIR/$LABEL.waits.diff"
    ;;
  *)
    usage
    ;;
esac
