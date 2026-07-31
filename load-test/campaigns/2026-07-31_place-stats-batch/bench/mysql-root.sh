#!/usr/bin/env bash
# 관측 전용 root 접속 래퍼.
#
# 왜 별도인가: performance_schema.data_locks · SHOW ENGINE INNODB STATUS · KILL은
# 애플리케이션 계정(solplyuser)에 권한이 없다 (실측: ERROR 1142 SELECT command denied).
# 측정 대상 쿼리는 반드시 mysql.sh(=앱 계정)로 돌리고, 이 래퍼는 **관측·정리에만** 쓴다 —
# 권한이 다른 계정으로 측정하면 재현하려는 경로가 달라진다.
set -euo pipefail
exec docker exec -i solply-bench-mysql \
  mysql --default-character-set=utf8mb4 \
        -uroot -prootpwd solply_bench_db "$@" 2> >(grep -v "Using a password" >&2)
