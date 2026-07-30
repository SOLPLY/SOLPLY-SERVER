#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
MYSQL="docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db"

echo "[seed] 시작: $(date +%T)"
node generate-bench-seed.mjs | $MYSQL
echo "[seed] 완료: $(date +%T)"
echo "[seed] 토큰 재생성 (7일 만료)"
cd .. && node scripts/generate-users-csv.mjs
