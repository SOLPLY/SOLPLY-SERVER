#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
MYSQL="docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db"

# 전제: bench 유저 10만 (#379 시드). 없으면 먼저 실행
USER_COUNT=$($MYSQL -N -e "SELECT COUNT(*) FROM users WHERE email LIKE 'bench\\_%@bench.local'")
if [ "$USER_COUNT" -lt 100000 ]; then
  echo "[seed-popular] bench 유저 없음 → 기존 시드 선행 실행"
  ./run-seed.sh
fi

echo "[seed-popular] 시작: $(date +%T)"
node generate-popular-seed.mjs | $MYSQL
echo "[seed-popular] 완료: $(date +%T)"
