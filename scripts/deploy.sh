#!/bin/bash
echo "🚀 배포 시작: $IMAGE_NAME"

echo "✅ docker-compose.yml 존재 확인:"
cat /home/ubuntu/docker-compose.yml

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