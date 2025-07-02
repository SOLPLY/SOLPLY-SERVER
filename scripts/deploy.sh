#!/bin/bash

IMAGE_NAME="$1"
COMPOSE_CONTENT="$2"

echo "🚀 배포 시작: $IMAGE_NAME"

cd /home/ubuntu/solply-server

# docker-compose.yml 안전하게 생성
cat > docker-compose.yml << EOF
$COMPOSE_CONTENT
EOF

echo "✅ docker-compose.yml 생성 완료"

# 이미지명 치환
sed -i "s|image: .*solply.*:.*|image: $IMAGE_NAME|g" docker-compose.yml

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