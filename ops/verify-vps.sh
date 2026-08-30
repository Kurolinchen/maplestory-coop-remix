#!/usr/bin/env bash
# TEMPLATE - NOT ACTIVE. Read-only readiness checks for the VPS.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."

log "Checking $VPS_HOST ..."
ssh -o BatchMode=yes -o ConnectTimeout=10 "$VPS_HOST" bash -s <<'REMOTE'
set -euo pipefail
echo "os:       $(. /etc/os-release && echo "$PRETTY_NAME")"
echo "docker:   $(docker --version 2>/dev/null || echo MISSING)"
echo "compose:  $(docker compose version 2>/dev/null || echo MISSING)"
echo "dev dir:  $(test -d /opt/maple-dev && echo present || echo MISSING)"
echo "prod dir: $(test -d /opt/maple-prod && echo present || echo MISSING)"
echo "dev net:  $(docker network inspect maple-dev-net >/dev/null 2>&1 && echo present || echo MISSING)"
echo "prod net: $(docker network inspect maple-prod-net >/dev/null 2>&1 && echo present || echo MISSING)"
echo "dev containers:";  docker ps --filter name=maple-dev  --format '  {{.Names}} {{.Status}}' || true
echo "prod containers:"; docker ps --filter name=maple-prod --format '  {{.Names}} {{.Status}}' || true
echo "opencode present (must be NO): $(command -v opencode >/dev/null 2>&1 && echo 'YES - VIOLATION' || echo no)"
REMOTE
log "Verification done."
