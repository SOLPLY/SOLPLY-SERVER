#!/bin/bash

echo "🚀 배포 시작"

cd /home/ubuntu/solply-server

echo "✅ docker-compose.yml 내용 확인"
cat docker-compose.yml  # 디버깅용

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