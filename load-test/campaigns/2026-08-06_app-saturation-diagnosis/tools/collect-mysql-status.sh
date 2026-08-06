#!/usr/bin/env bash
# MySQL 상태 카운터를 1초 간격으로 뜬다. **커넥션을 하나만 열고** 그 안으로 질의를 흘려보낸다 —
# 기존 sampler.sh처럼 매 틱 `docker exec mysql`을 새로 띄우면 계측이 DB 컨테이너 CPU를 먹고
# (가이드 A.6의 알려진 한계), 1초 간격에서는 그 비용이 3초 간격의 3배가 된다.
#
# 타임스탬프는 MySQL의 NOW(3)이다 — 같은 커널의 시계라 앱/DB cgroup 수집기와 정렬된다.
set -uo pipefail
N="${1:-180}"
SQL="SELECT UNIX_TIMESTAMP(NOW(3)),
  MAX(IF(VARIABLE_NAME='Threads_connected',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Threads_running',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Queries',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Innodb_row_lock_waits',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Innodb_row_lock_current_waits',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Innodb_row_lock_time',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Innodb_buffer_pool_reads',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Created_tmp_disk_tables',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Innodb_data_reads',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Innodb_data_writes',VARIABLE_VALUE,NULL)),
  MAX(IF(VARIABLE_NAME='Connection_errors_max_connections',VARIABLE_VALUE,NULL))
  FROM performance_schema.global_status;"

printf 'ts\tthreads_connected\tthreads_running\tqueries\trow_lock_waits\trow_lock_current_waits\trow_lock_time_ms\tbp_reads\ttmp_disk_tables\tdata_reads\tdata_writes\tconn_err_max\n'
{
  i=0
  while [ "$i" -lt "$N" ]; do
    printf '%s\n' "$SQL"
    sleep 1
    i=$((i + 1))
  done
} | docker exec -i solply-bench-mysql mysql -uroot -prootpwd -N -B 2>/dev/null
