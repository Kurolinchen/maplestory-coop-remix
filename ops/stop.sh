#!/usr/bin/env bash
# Stop the local dev environment (keeps the DB volume). Usage: ops/stop.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env
log "Stopping dev environment (DB data is kept in volume)..."
compose down
log "Stopped."
