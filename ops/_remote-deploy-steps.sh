#!/usr/bin/env bash
# Remote deploy steps of deploy-dev.sh (executed on the VPS, after
# ops/_remote-deploy-dev.sh checked out the requested commit).
set -euo pipefail

BRANCH="${1:?branch required}"
PUBLIC_HOST="${2:?public host required}"
REG_PUBLIC_ORIGIN="${3:?registration public origin required}"
REG_PUBLIC_HOST="${REG_PUBLIC_ORIGIN#https://}"
CONFIG=/opt/maple-dev/config
SECRETS=/opt/maple-dev/secrets
COMPOSE=(docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env")

# Render the runtime config without exposing the gitignored DB password.
set -a
source "$CONFIG/.env"
set +a
sed -e "s|^\( *\)DB_PASS:.*|\1DB_PASS: \"${DB_ROOT_PASSWORD}\"|" \
    -e "s|^\( *\)HOST:.*|\1HOST: ${PUBLIC_HOST}|" \
  config.yaml > "$CONFIG/config.dev.yaml"
chmod 600 "$CONFIG/config.dev.yaml"

for secret in reg_db_password reg_invite_passphrase; do
  [[ -s "$SECRETS/$secret" ]] || {
    printf 'Missing registration secret: %s\n' "$SECRETS/$secret" >&2
    exit 1
  }
done

cat > "$CONFIG/registration.env.tmp" <<EOF
REG_PUBLIC_ORIGIN=$REG_PUBLIC_ORIGIN
REG_JDBC_URL=jdbc:mysql://db:3306/cosmic?useSSL=false&allowPublicKeyRetrieval=true
REG_DB_USER=registration
REG_DB_PASSWORD_FILE=/run/secrets/reg_db_password
REG_INVITE_FILE=/run/secrets/reg_invite_passphrase
REG_PORT=8080
REG_PER_IP_BURST=2
REG_GLOBAL_HOURLY_CAP=20
REG_TRUSTED_PROXY_IPS=172.30.250.2
REG_RESOURCE_DIR=/opt/registration/public
EOF
chmod 600 "$CONFIG/registration.env.tmp"
mv "$CONFIG/registration.env.tmp" "$CONFIG/registration.env"
sed "s/\[REG_PUBLIC_HOST\]/$REG_PUBLIC_HOST/g" ops/Caddyfile.vps > "$CONFIG/Caddyfile.tmp"
mv "$CONFIG/Caddyfile.tmp" "$CONFIG/Caddyfile"
chmod 644 "$CONFIG/Caddyfile"

"${COMPOSE[@]}" config --quiet
"${COMPOSE[@]}" build maplestory registration
"${COMPOSE[@]}" pull caddy
"${COMPOSE[@]}" up -d db maplestory

ready=0
for _ in $(seq 1 180); do
  if "${COMPOSE[@]}" logs maplestory 2>/dev/null | grep 'Cosmic is now online' >/dev/null; then
    ready=1
    break
  fi
  state="$("${COMPOSE[@]}" ps --format '{{.State}}' maplestory 2>/dev/null || true)"
  [[ "$state" != "exited" && "$state" != "dead" ]] || break
  sleep 1
done
[[ "$ready" == 1 ]] || {
  "${COMPOSE[@]}" logs --tail=100 maplestory >&2 || true
  printf '%s\n' "Server did not become ready." >&2
  exit 1
}
for port in 8484 7575 7576 7577; do
  (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null \
    || { printf 'Port %s is not reachable.\n' "$port" >&2; exit 1; }
  exec 3>&- 3<&-
done

reg_password="$(tr -d '\r\n' < "$SECRETS/reg_db_password")"
[[ -n "$reg_password" ]] || { printf '%s\n' "Registration DB password is empty." >&2; exit 1; }
{
  printf "CREATE USER IF NOT EXISTS 'registration'@'172.30.251.3' IDENTIFIED BY '%s';\n" "$reg_password"
  printf "ALTER USER 'registration'@'172.30.251.3' IDENTIFIED BY '%s';\n" "$reg_password"
  printf "REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'registration'@'172.30.251.3';\n"
  printf "GRANT INSERT ON cosmic.accounts TO 'registration'@'172.30.251.3';\n"
} | "${COMPOSE[@]}" exec -T db sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot cosmic'
unset reg_password

grant_state="$("${COMPOSE[@]}" exec -T db sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B -e "
     SELECT COUNT(*) FROM mysql.user WHERE User = \"registration\" AND Host = \"172.30.251.3\";
     SELECT COUNT(*) FROM mysql.tables_priv
       WHERE User = \"registration\" AND Host = \"172.30.251.3\"
         AND Db = \"cosmic\" AND Table_name = \"accounts\"
         AND Table_priv = \"Insert\" AND Column_priv = \"\";
     SELECT COUNT(*) FROM mysql.db WHERE User = \"registration\";
     SELECT COUNT(*) FROM mysql.user WHERE User = \"registration\";"' \
  | paste -sd ' ' -)"
[[ "$grant_state" == "1 1 0 1" ]] || { printf 'Unexpected registration DB grants: %s\n' "$grant_state" >&2; exit 1; }

"${COMPOSE[@]}" up -d registration
registration_id="$("${COMPOSE[@]}" ps -q registration)"
for _ in $(seq 1 60); do
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "$registration_id" 2>/dev/null || true)" == healthy ]] && break
  sleep 1
done
[[ "$(docker inspect -f '{{.State.Health.Status}}' "$registration_id")" == healthy ]] || {
  "${COMPOSE[@]}" logs --tail=100 registration >&2 || true
  printf '%s\n' "Registration service did not become healthy." >&2
  exit 1
}

"${COMPOSE[@]}" run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
"${COMPOSE[@]}" up -d caddy
https_ready=0
for _ in $(seq 1 90); do
  if curl --fail --silent --show-error --max-time 5 "$REG_PUBLIC_ORIGIN/health/ready" | grep -qx ready; then
    https_ready=1
    break
  fi
  sleep 1
done
[[ "$https_ready" == 1 ]] || {
  "${COMPOSE[@]}" logs --tail=100 caddy >&2 || true
  printf '%s\n' "Public registration endpoint did not become ready." >&2
  exit 1
}
"${COMPOSE[@]}" ps
