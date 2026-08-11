#!/usr/bin/env bash
# 언두·더티·경합의 1초 시계열 — 사전 등록 §5의 1급 지표 중 "언두 페이지 읽기·히스토리 리스트 길이·
# 전역 더티 페이지 수"를 담당한다. 라운드 내내 돌며 대조 창과 배치 창을 같은 축에 올린다.
# 사용: BENCH_CAMPAIGN=... undo-sampler.sh <라벨> <지속초>   → results/metrics/<라벨>.undo.csv
#
# 08-07의 collect-mysql-status.sh와 같은 방식으로 **mysql 프로세스를 하나만 열고** 그 안으로 질의를
# 흘려보낸다. 매 틱 `docker exec`를 새로 띄우면 1초 간격에서는 계측 자체가 DB 컨테이너 CPU를 먹는다.
# 타임스탬프는 MySQL의 NOW(3)이다 — 같은 커널의 시계라 census·cgroup 수집기와 정렬된다.
#
# 간격은 "1초 + 질의 시간"이라 정확히 1Hz가 아니다. ts 컬럼이 정본이고, 행 번호를 시각으로 쓰지 않는다.
#
# 전제: buffer_page_read_undo_log는 module_buffer_page가 켜져 있어야 산다
#       (tools/enable-instrumentation.sql). 꺼져 있으면 그 칸은 0이 아니라 **NULL**로 찍힌다 —
#       "언두 읽기가 없었다"와 "안 세고 있었다"를 CSV에서 구분하기 위해서다.
set -euo pipefail
cd "$(dirname "$0")/../../.."          # -> load-test/

usage() {
  echo "usage: BENCH_CAMPAIGN=<캠페인 디렉터리명> $0 <label> <duration_s>" >&2
  exit 1
}
[ -n "${BENCH_CAMPAIGN:-}" ] || { echo "[undo] BENCH_CAMPAIGN 환경변수 필요 (캠페인 디렉터리명)" >&2; usage; }
LABEL="${1:-}"
DURATION="${2:-}"
[ -n "$LABEL" ] && [ -n "$DURATION" ] || usage

OUT_DIR="campaigns/$BENCH_CAMPAIGN/results/metrics"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$LABEL.undo.csv"

# CONCAT_WS로 서버가 CSV를 만든다. IFNULL을 각 칸에 감싸는 이유는 CONCAT_WS가 NULL 인자를
# **건너뛰어 칼럼을 밀어버리기** 때문이다 — 비활성 카운터는 문자열 "NULL"로 자리를 지킨다.
SQL='SELECT CONCAT_WS(",",
  UNIX_TIMESTAMP(NOW(3)),
  IFNULL((SELECT IF(STATUS = "enabled", `COUNT`, NULL) FROM information_schema.INNODB_METRICS
           WHERE NAME = "trx_rseg_history_len"), "NULL"),
  IFNULL((SELECT IF(STATUS = "enabled", `COUNT`, NULL) FROM information_schema.INNODB_METRICS
           WHERE NAME = "buffer_page_read_undo_log"), "NULL"),
  IFNULL(MAX(IF(VARIABLE_NAME = "Innodb_buffer_pool_pages_dirty", VARIABLE_VALUE, NULL)), "NULL"),
  IFNULL(MAX(IF(VARIABLE_NAME = "Innodb_row_lock_waits", VARIABLE_VALUE, NULL)), "NULL"),
  IFNULL(MAX(IF(VARIABLE_NAME = "Threads_running", VARIABLE_VALUE, NULL)), "NULL"))
  FROM performance_schema.global_status;'

{
  echo "ts,trx_rseg_history_len,buffer_page_read_undo_log,bp_pages_dirty,row_lock_waits,threads_running"
  {
    i=0
    while [ "$i" -lt "$DURATION" ]; do
      printf '%s\n' "$SQL"
      sleep 1
      i=$((i + 1))
    done
  } | docker exec -i solply-bench-mysql mysql -uroot -prootpwd -N -B 2>/dev/null
} > "$OUT"

echo "[undo] 저장: $OUT ($(( $(wc -l < "$OUT") - 1 ))샘플)"
