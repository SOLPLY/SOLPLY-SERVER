#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
echo "[seed] 시작: $(date +%T)"
docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db < seed-bench-data.sql
echo "[seed] 완료: $(date +%T)"
