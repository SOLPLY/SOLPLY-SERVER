#!/usr/bin/env bash
# 사용법: ./run-explain.sh <STATE라벨>   → results/<STATE>-explain.txt
set -euo pipefail
cd "$(dirname "$0")"
STATE="$1"
mkdir -p results
OUT="results/${STATE}-explain.txt"
MYSQL="docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db"

USER_ID=$($MYSQL -N -e "SELECT u.id FROM users u JOIN bookmarks b ON b.user_id=u.id \
  WHERE u.email LIKE 'bench\\_%@bench.local' LIMIT 1")
IDS=$($MYSQL -N -e "SELECT GROUP_CONCAT(id) FROM (SELECT id FROM places WHERE active=1 LIMIT 30) t")

run_q() {
  local name="$1" sql="$2"
  {
    echo "=== [$name] ==="
    echo "--- plan ---"
    echo "EXPLAIN $sql" | $MYSQL 2>&1
    echo "--- analyze x5 ---"
    for i in 1 2 3 4 5; do echo "EXPLAIN ANALYZE $sql" | $MYSQL -N 2>&1; done
    echo ""
  } >> "$OUT"
}

: > "$OUT"
echo "STATE=$STATE USER_ID=$USER_ID $(date)" >> "$OUT"

run_q "town-ordered-list" "SELECT b.target_id FROM bookmarks b INNER JOIN places p ON p.id=b.target_id \
WHERE b.user_id=${USER_ID} AND b.target_type='PLACE' AND p.town_id=2 AND p.active=true \
ORDER BY b.created_at DESC, b.target_id DESC"

run_q "folder-preview-window" "SELECT t.town_id, t.target_id FROM ( \
SELECT p.town_id AS town_id, b.target_id AS target_id, \
ROW_NUMBER() OVER (PARTITION BY p.town_id ORDER BY b.created_at DESC, b.target_id DESC) AS rn \
FROM bookmarks b INNER JOIN places p ON p.id=b.target_id \
WHERE b.user_id=${USER_ID} AND b.target_type='PLACE' AND p.active=true) t WHERE t.rn=1"

run_q "batch-in" "SELECT b.target_id FROM bookmarks b \
WHERE b.user_id=${USER_ID} AND b.target_type='PLACE' AND b.target_id IN (${IDS})"

run_q "exists" "SELECT EXISTS(SELECT 1 FROM bookmarks b \
WHERE b.user_id=${USER_ID} AND b.target_type='PLACE' AND b.target_id=1)"

echo "done -> $OUT"
