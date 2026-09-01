#!/usr/bin/env bash
# Set one offline LOCAL DEV character to GM level 4 for Milestone 0.1 playtesting.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

if [[ -o xtrace ]]; then
  die "Refusing to run with shell tracing enabled (would expose secrets)."
fi

[[ "$#" -eq 1 ]] || die "Usage: ops/set-dev-gm.sh <character-name>"
character_name="$1"
[[ "$character_name" =~ ^[A-Za-z0-9]{3,12}$ ]] \
  || die "Character name must match [A-Za-z0-9]{3,12}."

load_env
[[ "$COMPOSE_PROJECT" == "maple-coop-dev" ]] \
  || die "Refusing non-local Compose project: $COMPOSE_PROJECT"

server_id="$(compose ps --status running -q "$SERVER_SERVICE")"
[[ -z "$server_id" ]] \
  || die "Gameserver is running. Run ops/stop-server.sh before changing persisted GM state."

container_id="$(compose ps -q "$DB_SERVICE")"
[[ -n "$container_id" && "$container_id" != *$'\n'* ]] \
  || die "Local dev DB container is not running or is ambiguous. Run ops/start.sh first."

project_label="$(docker_cli inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id")"
service_label="$(docker_cli inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "$container_id")"
volume_name="$(docker_cli inspect --format '{{ range .Mounts }}{{ if eq .Destination "/var/lib/mysql" }}{{ .Name }}{{ end }}{{ end }}' "$container_id")"
[[ "$project_label" == "maple-coop-dev" ]] \
  || die "Refusing DB container from Compose project '$project_label'."
[[ "$service_label" == "$DB_SERVICE" ]] \
  || die "Refusing container with service label '$service_label'."
[[ "$volume_name" == "maple-coop-dev-db" ]] \
  || die "Refusing DB container with unexpected data volume '$volume_name'."

mysql_exec() (
  export MYSQL_PWD="$DB_ROOT_PASSWORD"
  compose exec -T -e MYSQL_PWD "$DB_SERVICE" mysql -uroot -N -B cosmic
)

lookup_sql="SELECT c.id, c.gm, a.loggedin FROM characters AS c JOIN accounts AS a ON a.id = c.accountid WHERE BINARY c.name = '${character_name}';"
row="$(printf '%s\n' "$lookup_sql" | mysql_exec)" \
  || die "Could not query local dev character."
[[ -n "$row" ]] || die "Character '$character_name' not found."
[[ "$row" != *$'\n'* ]] || die "Character lookup returned more than one row; refusing."

IFS=$'\t' read -r character_id previous_gm loggedin <<< "$row"
[[ "$character_id" =~ ^[0-9]+$ && "$previous_gm" =~ ^[0-9]+$ && "$loggedin" =~ ^[0-9]+$ ]] \
  || die "Character lookup returned unexpected data; refusing."
[[ "$loggedin" == "0" ]] \
  || die "Account for '$character_name' is still logged in (state $loggedin). Log out before changing GM level."

update_sql="START TRANSACTION;
UPDATE characters AS c JOIN accounts AS a ON a.id = c.accountid SET c.gm = 4 WHERE c.id = ${character_id} AND a.loggedin = 0;
SELECT ROW_COUNT();
SELECT c.gm, a.loggedin FROM characters AS c JOIN accounts AS a ON a.id = c.accountid WHERE c.id = ${character_id};
COMMIT;"
result="$(printf '%s\n' "$update_sql" | mysql_exec)" \
  || die "GM update failed; no success reported."
mapfile -t result_lines <<< "$result"
[[ "${#result_lines[@]}" -eq 2 ]] || die "GM update returned unexpected verification data."
changed_rows="${result_lines[0]}"
IFS=$'\t' read -r new_gm final_loggedin <<< "${result_lines[1]}"
[[ "$changed_rows" =~ ^[0-9]+$ && "$new_gm" == "4" && "$final_loggedin" == "0" ]] \
  || die "GM update was not safely applied; character may have logged in concurrently."

log "Local dev character '$character_name': GM level $previous_gm -> $new_gm ($changed_rows row changed)."
log "Log in again for the new GM level to take effect."
