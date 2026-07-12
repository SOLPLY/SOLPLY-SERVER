#!/usr/bin/env bash
# 사용법: ./apply-state.sh S0|S1|S2a|S2b
set -euo pipefail
cd "$(dirname "$0")"
STATE="$1"
[ -f "index-states/${STATE}.sql" ] || { echo "unknown state: $STATE"; exit 1; }
MYSQL="docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db"
echo "[index] reset..."
$MYSQL --force < index-states/reset.sql 2>/dev/null || true
echo "[index] apply ${STATE}..."
$MYSQL < "index-states/${STATE}.sql"
$MYSQL -e "ANALYZE TABLE bookmarks;" > /dev/null
$MYSQL -e "SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
           FROM information_schema.STATISTICS
           WHERE TABLE_SCHEMA='solply_bench_db' AND TABLE_NAME='bookmarks'
           GROUP BY INDEX_NAME"
echo "[index] ${STATE} done"
