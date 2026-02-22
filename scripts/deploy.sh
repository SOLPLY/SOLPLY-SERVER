#!/bin/bash
set -euo pipefail

ENV_NAME="${1:-}"
ACTION="${2:-up}"   # up|down|restart|pull|ps|logs
BASE_DIR="/home/ubuntu/solply-server/env"

if [[ "$ENV_NAME" != "dev" && "$ENV_NAME" != "prod" ]]; then
  echo "Usage: ./scripts/deploy.sh <dev|prod> [up|down|restart|pull|ps|logs]"
  exit 1
fi

PROJECT="solply-${ENV_NAME}"
COMPOSE_FILE="${BASE_DIR}/${ENV_NAME}/docker-compose.yml"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "❌ compose file not found: $COMPOSE_FILE"
  exit 1
fi

echo "✅ ENV=$ENV_NAME ACTION=$ACTION"
echo "✅ PROJECT=$PROJECT"
echo "✅ COMPOSE=$COMPOSE_FILE"

cd "$(dirname "$COMPOSE_FILE")"

case "$ACTION" in
  pull)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" pull app || true
    ;;
  up)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" pull app || true
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d
    ;;
  restart)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" pull app || true
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" down || true
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d
    ;;
  down)
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" down || true
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

echo "✅ Done."