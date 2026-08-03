#!/usr/bin/env bash
# [디버깅용] 호스트에서 앱 기동 — 맥북 전체 성능이 나와 실서버 체급 측정이 아니다.
# 정식 벤치 기동은 컨테이너: ./gradlew bootJar && docker compose -f docker/docker-compose.bench.yml up -d --build bench-app
# (2 vCPU/2G, Tomcat threads 50, Hikari 10 — 부하 모델 기반 리소스 제한)
#
# 벤치 DB(3310)·Redis(6382)를 바라보는 앱을 호스트에서 기동.
# 사용법: ./run-app.sh [작업디렉토리]  (기본: 리포 루트, baseline 측정 시 worktree 경로 전달)
set -euo pipefail
APP_DIR="${1:-$(git rev-parse --show-toplevel)}"
cd "$APP_DIR"
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3310/solply_bench_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul&characterEncoding=utf8" \
SPRING_DATASOURCE_USERNAME=solplyuser \
SPRING_DATASOURCE_PASSWORD=solplyuserpwd \
SPRING_DATA_REDIS_HOST=localhost \
SPRING_DATA_REDIS_PORT=6382 \
SPRING_DATA_REDIS_PASSWORD=solplyredispwd \
JAVA_TOOL_OPTIONS="-Xmx2g" \
./gradlew bootRun
