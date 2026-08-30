#!/usr/bin/env bash
# Start the local dev environment (DB + server). Usage: ops/start.sh [--build]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

ensure_env
load_env
render_config

if [[ "${1:-}" == "--build" ]]; then
  log "Building server image and starting..."
  compose up -d --build
else
  log "Starting (use --build to force an image rebuild)..."
  compose up -d
fi

compose ps
log "Started. Logs: ops/logs.sh server | Smoke test: ops/smoke-test.sh"
log "Client ports: login 8484, world 1 channels 7575-7577."
