#!/usr/bin/env bash
# Rotate the LOCAL DEV database root password (ops/.env + running MySQL volume).
# Safe to re-run; never prints the new password. Does NOT touch any production system.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if [[ -o xtrace ]]; then
  die "Refusing to run with shell tracing enabled (would expose secrets)."
fi

command -v openssl >/dev/null 2>&1 || die "openssl not found."

ensure_env
load_env
OLD_PW="$DB_ROOT_PASSWORD"
NEW_PW="$(openssl rand -hex 24)"

mysql_exec() { # $1 = root password, SQL on stdin
  compose exec -T -e "MYSQL_PWD=$1" "$DB_SERVICE" mysql -uroot -N -B
}

db_reachable() {
  printf 'SELECT 1;\n' | mysql_exec "$OLD_PW" 2>/dev/null | grep -q '^1$'
}

if ! db_reachable; then
  log "Starting $DB_SERVICE to perform the rotation..."
  compose up -d "$DB_SERVICE" >/dev/null
  for _ in $(seq 1 90); do
    db_reachable && break
    sleep 1
  done
  db_reachable || die "DB did not become reachable with the current password; aborting without changes."
fi

log "Rotating MySQL root credentials..."
hosts="$(printf "SELECT host FROM mysql.user WHERE user = 'root';\n" | mysql_exec "$OLD_PW")" \
  || die "Could not read mysql.user; aborting without changes."
[[ -n "$hosts" ]] || die "No root users found; aborting without changes."

sql=""
while IFS= read -r host; do
  [[ -n "$host" ]] || continue
  sql+="ALTER USER 'root'@'${host}' IDENTIFIED BY '${NEW_PW}'; "
done <<< "$hosts"
sql+="FLUSH PRIVILEGES;"

if ! printf '%s\n' "$sql" | mysql_exec "$OLD_PW" >/dev/null; then
  die "ALTER USER failed; aborting without touching ops/.env."
fi

printf 'SELECT 1;\n' | mysql_exec "$NEW_PW" | grep -q '^1$' \
  || die "Verification with the NEW password failed; ops/.env left unchanged."

sed -i "s/^DB_ROOT_PASSWORD=.*/DB_ROOT_PASSWORD=${NEW_PW}/" "$ENV_FILE"
chmod 600 "$ENV_FILE"
render_config

if compose ps --format json "$SERVER_SERVICE" 2>/dev/null | grep -q '"State":"running"'; then
  log "Restarting $SERVER_SERVICE with the new credentials..."
  marker_count_before="$(compose logs "$SERVER_SERVICE" 2>/dev/null | grep -c "$ONLINE_MARKER" || true)"
  compose restart "$SERVER_SERVICE" >/dev/null
  for _ in $(seq 1 120); do
    count="$(compose logs "$SERVER_SERVICE" 2>/dev/null | grep -c "$ONLINE_MARKER" || true)"
    [[ "${count:-0}" -gt "${marker_count_before:-0}" ]] && break
    sleep 1
  done
  count="$(compose logs "$SERVER_SERVICE" 2>/dev/null | grep -c "$ONLINE_MARKER" || true)"
  [[ "${count:-0}" -gt "${marker_count_before:-0}" ]] \
    || die "Server did not come back online after restart; check ops/logs.sh server."
fi

log "Dev DB password rotated: MySQL updated, $ENV_FILE + rendered config updated."
log "Verify anytime with: ops/smoke-test.sh"
