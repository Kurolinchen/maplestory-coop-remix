#!/usr/bin/env bash
# TEMPLATE - NOT ACTIVE. Remote dev logs. Usage: ops/dev-logs.sh [server|db]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."

service="${1:-server}"
ssh "$VPS_HOST" bash -s -- "$service" <<'REMOTE'
set -euo pipefail
svc="${1:-server}"
cd /opt/maple-dev
case "$svc" in
  server) svc="maplestory" ;;
  db)     svc="db" ;;
esac
docker compose -f config/docker-compose.yml logs -f --tail=200 "$svc"
REMOTE
