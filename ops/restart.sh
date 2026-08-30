#!/usr/bin/env bash
# Restart the local dev environment. Usage: ops/restart.sh [--build]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

"$OPS_DIR/stop.sh"
"$OPS_DIR/start.sh" "$@"
