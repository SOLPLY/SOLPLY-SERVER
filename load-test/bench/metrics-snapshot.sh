#!/usr/bin/env bash
# MySQL 누적 카운터의 라운드 구간 diff 리포트.
# 사용: ./metrics-snapshot.sh start <라벨>   (부하 시작 직전)
#       ./metrics-snapshot.sh report <라벨>  (부하 종료 직후 → diff 출력·저장)
set -euo pipefail
cd "$(dirname "$0")"
LABEL="${2:?라벨 필요 (예: popular-v0-r1)}"
SNAP_DIR="results/metrics"
mkdir -p "$SNAP_DIR"

dump() {
  docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd -N -e "SHOW GLOBAL STATUS" 2>/dev/null
}

case "${1:-}" in
  start)
    dump > "$SNAP_DIR/$LABEL.start"
    echo "[metrics] start 스냅샷: $SNAP_DIR/$LABEL.start"
    ;;
  report)
    dump > "$SNAP_DIR/$LABEL.end"
    awk 'NR==FNR {a[$1]=$2; next} {b[$1]=$2}
      END {
        rr = b["Innodb_buffer_pool_read_requests"] - a["Innodb_buffer_pool_read_requests"];
        dr = b["Innodb_buffer_pool_reads"] - a["Innodb_buffer_pool_reads"];
        printf "buffer_pool_read_requests  %d\n", rr;
        printf "buffer_pool_disk_reads     %d\n", dr;
        if (rr > 0) printf "buffer_pool_hit_rate       %.4f%%\n", (1 - dr / rr) * 100;
        printf "innodb_data_reads          %d\n", b["Innodb_data_reads"] - a["Innodb_data_reads"];
        printf "innodb_data_writes         %d\n", b["Innodb_data_writes"] - a["Innodb_data_writes"];
        printf "innodb_data_fsyncs         %d\n", b["Innodb_data_fsyncs"] - a["Innodb_data_fsyncs"];
        printf "questions                  %d\n", b["Questions"] - a["Questions"];
        printf "select_scan                %d\n", b["Select_scan"] - a["Select_scan"];
        printf "created_tmp_disk_tables    %d\n", b["Created_tmp_disk_tables"] - a["Created_tmp_disk_tables"];
      }' "$SNAP_DIR/$LABEL.start" "$SNAP_DIR/$LABEL.end" | tee "$SNAP_DIR/$LABEL.diff"
    ;;
  *)
    echo "usage: $0 {start|report} <label>" >&2
    exit 1
    ;;
esac
