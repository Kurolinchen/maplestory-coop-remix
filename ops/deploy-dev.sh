#!/usr/bin/env bash
# TEMPLATE - NOT ACTIVE. Deploy the DEV instance to the VPS. Requires owner approval.
# Production deployment is a separate, always-human-approved process (not in this script).
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
[[ "${1:-}" == "--i-understand" ]] || die "Refusing: requires '--i-understand' AND explicit owner approval."

confirm "Deploy current development branch to DEV on $VPS_HOST?"

BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
COMMIT="$(git -C "$REPO_ROOT" rev-parse --short HEAD)"
log "Deploying branch=$BRANCH commit=$COMMIT to $VPS_HOST:/opt/maple-dev"

ssh "$VPS_HOST" bash -s -- "$BRANCH" <<'REMOTE'
set -euo pipefail
BRANCH="${1:-development}"
cd /opt/maple-dev

# Source of truth is the GitHub fork; the VPS pulls, builds and restarts.
if [[ ! -d .git ]]; then
  git clone https://github.com/Kurolinchen/maplestory-coop-remix.git .
fi
git fetch origin
git checkout "$BRANCH"
git reset --hard "origin/$BRANCH"

# Compose file/secrets for the VPS live in /opt/maple-dev/config (managed separately,
# never committed). This template expects /opt/maple-dev/config/docker-compose.yml and
# /opt/maple-dev/config/.env to exist (created during manual setup).
docker compose -f config/docker-compose.yml --env-file config/.env up -d --build
docker compose -f config/docker-compose.yml ps
REMOTE

log "Remote dev deploy finished (branch=$BRANCH commit=$COMMIT). Check: ops/dev-status.sh"
