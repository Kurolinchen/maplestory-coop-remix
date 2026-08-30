#!/usr/bin/env bash
# Tail logs. Usage: ops/logs.sh [server|db]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env
service="${1:-server}"
case "$service" in
  server) service="$SERVER_SERVICE" ;;
  db)     service="$DB_SERVICE" ;;
  *) die "Unknown service '$service' (use: server|db)" ;;
esac
compose logs -f --tail=200 "$service"
