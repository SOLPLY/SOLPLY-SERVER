#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# --default-character-set=utf8mb4 필수 — 없으면 클라이언트가 latin1로 접속해
# 한글 장소명이 이중 인코딩된다 (벤치플레이스 → ë²¤ì¹˜í”Œë ˆì´ìŠ¤).
MYSQL="docker exec -i solply-bench-mysql mysql --default-character-set=utf8mb4 -usolplyuser -psolplyuserpwd solply_bench_db"

echo "[seed] 시작: $(date +%T)"
node generate-bench-seed.mjs | $MYSQL
echo "[seed] 완료: $(date +%T)"
echo "[seed] 토큰 재생성 (7일 만료)"
cd .. && node scripts/generate-users-csv.mjs
