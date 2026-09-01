#!/usr/bin/env bash
# Remote dev status.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."

ssh "$VPS_HOST" bash -s <<'REMOTE'
set -euo pipefail
cd /opt/maple-dev/app
echo "== git =="; git log --oneline -3 2>/dev/null || true
echo "== compose =="; docker compose -f ../config/docker-compose.yml --env-file ../config/.env ps 2>/dev/null || true
echo "== db volume =="; docker volume ls --filter name=maple-dev || true
REMOTE
