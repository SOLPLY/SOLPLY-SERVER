#!/bin/bash
set -euo pipefail

ENV_NAME="${1:-}"
ACTION="${2:-up}"
BASE_DIR="/home/ubuntu/solply-server/env"

if [[ "$ENV_NAME" != "dev" && "$ENV_NAME" != "prod" ]]; then
  echo "Usage: ./scripts/deploy.sh <dev|prod> [up|down|restart|pull|ps|logs]"
  exit 1
fi

PROJECT="solply-${ENV_NAME}"
COMPOSE_FILE="${BASE_DIR}/${ENV_NAME}/docker-compose.yml"
SERVICE="${ENV_NAME}-app"   # ✅ 핵심: dev-app / prod-app

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "❌ compose file not found: $COMPOSE_FILE"
  exit 1
fi

echo "✅ ENV=$ENV_NAME ACTION=$ACTION"
echo "✅ PROJECT=$PROJECT"
echo "✅ COMPOSE=$COMPOSE_FILE"
echo "✅ SERVICE=$SERVICE"

cd "$(dirname "$COMPOSE_FILE")"

case "$ACTION" in
  pull)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" pull "$SERVICE"
    ;;
  up)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" pull "$SERVICE"
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d --force-recreate --no-deps "$SERVICE"
    ;;
  restart)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" pull "$SERVICE"
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d --force-recreate --no-deps "$SERVICE"
    ;;
  down)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" down
    ;;
  ps)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" ps
    ;;
  logs)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" logs -f --tail=200
    ;;
  *)
    echo "Unknown action: $ACTION"
    exit 1
    ;;
esac