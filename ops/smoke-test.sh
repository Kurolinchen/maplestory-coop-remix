#!/usr/bin/env bash
# Smoke test: boot DB + server, wait for "Cosmic is now online", check login port, stop again.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

TIMEOUT_S="${SMOKE_TIMEOUT_S:-180}"

ensure_env
load_env
render_config

log "Starting stack for smoke test..."
compose up -d

log "Waiting up to ${TIMEOUT_S}s for '$ONLINE_MARKER'..."
ok=0
for _ in $(seq 1 "$TIMEOUT_S"); do
  if compose logs "$SERVER_SERVICE" 2>/dev/null | grep -q "$ONLINE_MARKER"; then
    ok=1
    break
  fi
  # fail fast if the server container died
  state="$(compose ps --format json "$SERVER_SERVICE" 2>/dev/null | grep -o '"State":"[a-z]*"' | head -n1 || true)"
  if [[ "$state" == *'"State":"exited"'* || "$state" == *'"State":"dead"'* ]]; then
    warn "Server container exited early."
    break
  fi
  sleep 1
done

port_ok=0
if (exec 3<>/dev/tcp/127.0.0.1/8484) 2>/dev/null; then
  exec 3>&- 3<&- || true
  port_ok=1
fi

if [[ "$ok" == 1 && "$port_ok" == 1 ]]; then
  log "SMOKE TEST PASSED: server online, login port 8484 reachable."
  result=0
else
  [[ "$ok" == 1 ]] || warn "Online marker not found within ${TIMEOUT_S}s."
  [[ "$port_ok" == 1 ]] || warn "Login port 8484 not reachable."
  warn "Last server log lines:"
  compose logs --tail=80 "$SERVER_SERVICE" >&2 || true
  result=1
fi

log "Stopping stack after smoke test..."
compose down
exit "$result"
