#!/usr/bin/env bash
# Set one offline character on the VPS DEV instance to a GM level (0..6).
# Remote counterpart of ops/set-dev-gm.sh: same guards, scoped to /opt/maple-dev
# and Compose project maple-dev. Operational data change, not a migration.
# Never prints DB credentials.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if [[ -o xtrace ]]; then
  die "Refusing to run with shell tracing enabled (would expose secrets)."
fi

usage() { die "Usage: ops/set-vps-dev-gm.sh <character-name> <0..6> --i-understand"; }

[[ "$#" -eq 3 ]] || usage
character_name="$1"
gm_level="$2"
[[ "$3" == "--i-understand" ]] || usage

[[ "$character_name" =~ ^[A-Za-z0-9]{3,12}$ ]] \
  || die "Character name must match [A-Za-z0-9]{3,12}."
[[ "$gm_level" =~ ^[0-9]{1,3}$ ]] \
  || die "GM level must be a whole number between 0 and 6."
# Base-10 forced: a value like 08 must not be read as octal (and must not abort
# the script), and the 3-digit cap keeps the comparison free of overflow.
(( 10#$gm_level <= 6 )) \
  || die "GM level $gm_level is out of range (supported: 0..6)."

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
command -v ssh >/dev/null 2>&1 || die "ssh not found."

result="$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$VPS_HOST" \
  bash -s -- "$character_name" "$gm_level" <<'REMOTE'
set -euo pipefail

CHARACTER_NAME="$1"
GM_LEVEL="$2"
ROOT=/opt/maple-dev
APP="$ROOT/app"
CONFIG="$ROOT/config"
COMPOSE_FILE="$CONFIG/docker-compose.yml"
ENV_FILE="$CONFIG/.env"

fail() { printf '%s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || fail "docker not found on this host."

# Hard scope: this script only ever touches the maple-dev layout.
[[ "$ROOT" == "/opt/maple-dev" ]] || fail "Refusing unexpected remote root: $ROOT"
[[ -d "$APP" && -f "$COMPOSE_FILE" && -f "$ENV_FILE" ]] \
  || fail "VPS dev layout incomplete ($APP / $COMPOSE_FILE / $ENV_FILE)."

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"; }

# 1. Compose project must be maple-dev (source of truth: rendered compose name).
project_name="$(compose config 2>/dev/null | awk '/^name:[[:space:]]*/{print $2; exit}')" \
  || fail "Could not resolve the remote Compose project; refusing."
[[ "$project_name" == "maple-dev" ]] \
  || fail "Refusing non-dev Compose project: '${project_name:-none}'"

# 2. Gameserver must be stopped: a running server would save in-memory state
#    over the change.
server_running="$(compose ps --status running --format '{{.Service}}' maplestory 2>/dev/null || true)"
[[ -z "$server_running" ]] \
  || fail "Gameserver service is running. Stop the dev gameserver before changing persisted GM state."

# 3. DB must be running, belong to this project and use the maple-dev-db volume.
db_container="$(compose ps -q db 2>/dev/null || true)"
[[ -n "$db_container" && "$db_container" != *$'\n'* ]] \
  || fail "Dev DB container is not running or is ambiguous."
project_label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$db_container")"
service_label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "$db_container")"
volume_name="$(docker inspect --format '{{ range .Mounts }}{{ if eq .Destination "/var/lib/mysql" }}{{ .Name }}{{ end }}{{ end }}' "$db_container")"
[[ "$project_label" == "maple-dev" ]] || fail "Refusing DB container from Compose project '$project_label'."
[[ "$service_label" == "db" ]] || fail "Refusing container with service label '$service_label'."
[[ "$volume_name" == "maple-dev-db" ]] || fail "Refusing DB container with unexpected data volume '$volume_name'."

# 4. Credentials stay on the VPS: sourced into this shell, never printed.
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
[[ -n "${DB_ROOT_PASSWORD:-}" ]] || fail "DB_ROOT_PASSWORD missing in remote env file."

mysql_exec() {
  compose exec -T -e "MYSQL_PWD=${DB_ROOT_PASSWORD}" db mysql -uroot -N -B cosmic
}

lookup_sql="SELECT c.id, c.gm, a.loggedin, a.banned FROM characters AS c JOIN accounts AS a ON a.id = c.accountid WHERE BINARY c.name = '${CHARACTER_NAME}';"
row="$(printf '%s\n' "$lookup_sql" | mysql_exec)" || fail "Could not query dev character."
[[ -n "$row" ]] || fail "Character '$CHARACTER_NAME' not found."
[[ "$row" != *$'\n'* ]] || fail "Character lookup returned more than one row; refusing."

IFS=$'\t' read -r character_id previous_gm loggedin banned <<< "$row"
[[ "$character_id" =~ ^[0-9]+$ && "$previous_gm" =~ ^[0-9]+$ && "$loggedin" =~ ^[0-9]+$ && "$banned" =~ ^[0-9]+$ ]] \
  || fail "Character lookup returned unexpected data; refusing."
[[ "$loggedin" == "0" ]] \
  || fail "Account for '$CHARACTER_NAME' is still logged in (state $loggedin). Log out before changing GM level."
[[ "$banned" == "0" ]] \
  || fail "Account for '$CHARACTER_NAME' is banned (state $banned); refusing to grant GM rights."

update_sql="START TRANSACTION;
UPDATE characters AS c JOIN accounts AS a ON a.id = c.accountid SET c.gm = ${GM_LEVEL} WHERE c.id = ${character_id} AND a.loggedin = 0 AND a.banned = 0;
SELECT ROW_COUNT();
SELECT c.gm, a.loggedin FROM characters AS c JOIN accounts AS a ON a.id = c.accountid WHERE c.id = ${character_id};
COMMIT;"
update_result="$(printf '%s\n' "$update_sql" | mysql_exec)" \
  || fail "GM update failed; no success reported."
mapfile -t result_lines <<< "$update_result"
[[ "${#result_lines[@]}" -eq 2 ]] || fail "GM update returned unexpected verification data."
changed_rows="${result_lines[0]}"
IFS=$'\t' read -r new_gm final_loggedin <<< "${result_lines[1]}"
[[ "$changed_rows" == "1" && "$new_gm" == "$GM_LEVEL" && "$final_loggedin" == "0" ]] \
  || fail "GM update was not safely applied; character may have logged in concurrently."

printf 'RESULT %s %s %s %s\n' "$character_id" "$previous_gm" "$new_gm" "$changed_rows"
REMOTE
)" || die "Remote GM update failed; no success reported."

mapfile -t result_lines <<< "$result"
[[ "${#result_lines[@]}" -eq 1 ]] || die "Unexpected remote output; refusing to report success."
read -r marker character_id previous_gm new_gm changed_rows <<< "${result_lines[0]}"
[[ "$marker" == "RESULT" && "$character_id" =~ ^[0-9]+$ && "$previous_gm" =~ ^[0-9]+$ \
   && "$new_gm" == "$gm_level" && "$changed_rows" == "1" ]] \
  || die "Remote reported an unverified GM change; refusing to report success."

log "VPS dev character '$character_name' (id $character_id): GM level $previous_gm -> $new_gm ($changed_rows row changed)."
log "Log in again for the new GM level to take effect."
