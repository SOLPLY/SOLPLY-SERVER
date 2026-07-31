#!/usr/bin/env bash
# 벤치 DB에 V22·V23·V24를 직접 적용한다.
#
# 왜 앱(Flyway)이 아니라 손으로 적용하는가:
#   1) 이 캠페인의 7개 항목이 전부 DB 레벨이라 앱이 필요 없다
#   2) 벤치 앱 이미지 빌드가 레지스트리 접근에 막혀 있다
#      (eclipse-temurin:21-jre-alpine pull 시 proxyconnect i/o timeout)
#
# flyway_schema_history에 기록까지 하는 이유:
#   기록하지 않으면 나중에 이 DB에 앱을 붙이는 순간 Flyway가 V22를 다시 적용하려 들고
#   "Duplicate column name 'rating'"으로 기동이 깨진다.
#   checksum은 NULL로 둔다 — 파일 CRC를 손으로 계산하지 않아도 Flyway가 검증을 건너뛴다.
#   installed_by='bench-manual'로 정상 경로가 아님을 남긴다.
#
# 멱등하지 않다. 이미 적용된 DB에 다시 돌리면 실패한다 — 적용 여부는 아래로 확인한다:
#   ./mysql.sh -e "SELECT version, installed_by FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"
set -euo pipefail
cd "$(dirname "$0")"
MIG=../../../../src/main/resources/db/migration

echo "[V22] rating 컬럼 + 리뷰 커버링 인덱스  $(date +%T)"
./mysql.sh < $MIG/V22__add_rating_to_place_reviews.sql

# 1,039만 행 인덱스 재구축. 실측 21초 (버퍼풀 2G, in-place DDL).
# V23 주석의 운영 경고(수 분 단위 / MDL 대기)는 이보다 열악한 환경 기준이다.
echo "[V23] idx_bookmark_target 재구축 (1,039만 행)  $(date +%T)"
time ./mysql.sh < $MIG/V23__recreate_bookmark_target_index_with_created_at.sql

echo "[V24] place_stats  $(date +%T)"
./mysql.sh < $MIG/V24__create_place_stats.sql

echo "[history] flyway_schema_history 기록  $(date +%T)"
./mysql.sh <<'SQL'
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT MAX(installed_rank)+1, '22', 'add rating to place reviews', 'SQL',
       'V22__add_rating_to_place_reviews.sql', NULL, 'bench-manual', NOW(), 0, 1 FROM flyway_schema_history;
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT MAX(installed_rank)+1, '23', 'recreate bookmark target index with created at', 'SQL',
       'V23__recreate_bookmark_target_index_with_created_at.sql', NULL, 'bench-manual', NOW(), 21029, 1 FROM flyway_schema_history;
INSERT INTO flyway_schema_history
  (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success)
SELECT MAX(installed_rank)+1, '24', 'create place stats', 'SQL',
       'V24__create_place_stats.sql', NULL, 'bench-manual', NOW(), 0, 1 FROM flyway_schema_history;
SQL

echo "[완료] $(date +%T)"
./mysql.sh -e "SELECT version, description, installed_by FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 4;"
