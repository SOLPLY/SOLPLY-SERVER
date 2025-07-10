#!/bin/bash
COMPOSE_CONTENT="$1"

echo "🚀 배포 시작: $IMAGE_NAME"

cd /home/ubuntu/solply-server

echo "$COMPOSE_CONTENT" > docker-compose.yml
echo "✅ docker-compose.yml 생성 완료"

# 배포 실행
docker-compose pull app || true
docker-compose down || true
docker-compose up -d

# 확인
sleep 10
docker-compose ps

# 정리
docker image prune -f

echo "✅ 배포 완료!"