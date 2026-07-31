#!/usr/bin/env bash
# 벤치 MySQL 접속 래퍼.
#
# --default-character-set=utf8mb4 는 선택이 아니다 — 없으면 클라이언트가 latin1로 접속해
# 한글이 이중 인코딩된다 (벤치플레이스 → ë²¤ì¹˜í”Œë ˆì´ìŠ¤). HANDOFF §4 함정 3.
#
# 사용:
#   ./mysql.sh -e "SELECT 1;"          # 인자 그대로 전달
#   ./mysql.sh < some.sql              # stdin 파이프
set -euo pipefail
exec docker exec -i solply-bench-mysql \
  mysql --default-character-set=utf8mb4 \
        -usolplyuser -psolplyuserpwd solply_bench_db "$@" 2> >(grep -v "Using a password" >&2)
