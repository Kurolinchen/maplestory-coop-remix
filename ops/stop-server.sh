#!/usr/bin/env bash
# Stop only the local dev gameserver; keep MySQL running for guarded maintenance.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

[[ "$#" -eq 0 ]] || die "Usage: ops/stop-server.sh"
load_env
compose stop "$SERVER_SERVICE"
log "Local dev gameserver stopped; database remains running."
