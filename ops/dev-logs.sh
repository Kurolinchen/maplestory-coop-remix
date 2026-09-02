#!/usr/bin/env bash
# Remote dev logs. Usage: ops/dev-logs.sh [server|db|registration|caddy]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."

service="${1:-server}"
ssh "$VPS_HOST" bash -s -- "$service" <<'REMOTE'
set -euo pipefail
svc="${1:-server}"
cd /opt/maple-dev/app
case "$svc" in
  server) svc="maplestory" ;;
  db)     svc="db" ;;
  registration|caddy) ;;
  *) printf 'Unknown service: %s\n' "$svc" >&2; exit 1 ;;
esac
docker compose -f ../config/docker-compose.yml --env-file ../config/.env logs -f --tail=200 "$svc"
REMOTE
