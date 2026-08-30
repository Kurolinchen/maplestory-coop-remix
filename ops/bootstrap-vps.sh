#!/usr/bin/env bash
# TEMPLATE - NOT ACTIVE. Bootstraps a VPS for MapleStory dev+prod (idempotent).
# Layout: /opt/maple-dev and /opt/maple-prod fully isolated (dirs, compose projects,
# volumes, networks, config, secrets). No OpenCode / no LLM keys on the VPS. Ever.
# See docs/DEPLOYMENT.md. Run only with owner approval.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
[[ "${1:-}" == "--i-understand" ]] || die "Refusing: requires '--i-understand' AND explicit owner approval."

ssh "$VPS_HOST" bash -s <<'REMOTE'
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

# 1. Docker (skip if present)
if ! command -v docker >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y docker.io docker-compose-v2
  systemctl enable --now docker
fi

# 2. Isolated layouts for dev and prod
for env in dev prod; do
  mkdir -p "/opt/maple-$env/config"
  docker network inspect "maple-$env-net" >/dev/null 2>&1 || docker network create "maple-$env-net"
done

# 3. Non-root service user (optional hardening)
id maple >/dev/null 2>&1 || useradd --system --home /opt/maple-dev maple
usermod -aG docker maple || true

echo "VPS bootstrap finished."
REMOTE

log "Bootstrap reported success for $VPS_HOST. Next: ops/verify-vps.sh"
