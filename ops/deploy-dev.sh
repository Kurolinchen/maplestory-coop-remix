#!/usr/bin/env bash
# Deploy the DEV instance to the VPS. Requires owner approval.
# Production deployment is a separate, always-human-approved process (not in this script).
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
VPS_REG_PUBLIC_ORIGIN="${VPS_REG_PUBLIC_ORIGIN:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
[[ "$VPS_REG_PUBLIC_ORIGIN" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] \
  || die "VPS_REG_PUBLIC_ORIGIN must be an https origin containing only a hostname."
[[ "${1:-}" == "--i-understand" ]] || die "Refusing: requires '--i-understand' AND explicit owner approval."

confirm "Deploy current development branch to DEV on $VPS_HOST?"

BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
# shellcheck disable=SC1091
source "$REPO_ROOT/tools/client/profiles.env"
PUBLIC_HOST="${PROFILE_vps_dev_ip:-}"
[[ "$PUBLIC_HOST" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] \
  || die "PROFILE_vps_dev_ip is not a configured IPv4 address."
[[ -z "$(git -C "$REPO_ROOT" status --porcelain)" ]] \
  || die "Refusing to deploy a dirty worktree. Commit and push the reviewed changes first."
git -C "$REPO_ROOT" merge-base --is-ancestor "$COMMIT" "origin/$BRANCH" \
  || die "Refusing to deploy an unpushed commit: $COMMIT"
log "Deploying branch=$BRANCH commit=${COMMIT:0:12} to $VPS_HOST:/opt/maple-dev"

ssh "$VPS_HOST" bash -s -- "$BRANCH" "$COMMIT" "$PUBLIC_HOST" "$VPS_REG_PUBLIC_ORIGIN" <<'REMOTE'
set -euo pipefail
BRANCH="${1:-development}"
EXPECTED_COMMIT="${2:?expected commit required}"
PUBLIC_HOST="${3:?public host required}"
REG_PUBLIC_ORIGIN="${4:?registration public origin required}"
REG_PUBLIC_HOST="${REG_PUBLIC_ORIGIN#https://}"
APP=/opt/maple-dev/app
CONFIG=/opt/maple-dev/config
SECRETS=/opt/maple-dev/secrets
COMPOSE=(docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env")

# Source of truth is the GitHub fork; the VPS pulls, builds and restarts.
if [[ ! -d "$APP/.git" ]]; then
  rmdir "$APP" 2>/dev/null || true
  git clone --branch "$BRANCH" --single-branch \
    https://github.com/Kurolinchen/maplestory-coop-remix.git "$APP"
else
  cd "$APP"
  [[ -z "$(git status --porcelain)" ]] || { printf '%s\n' "Remote app worktree is dirty; refusing." >&2; exit 1; }
  git fetch origin "$BRANCH"
  git switch "$BRANCH"
  git merge --ff-only "origin/$BRANCH"
fi
cd "$APP"
[[ "$(git rev-parse HEAD)" == "$EXPECTED_COMMIT" ]] \
  || { printf '%s\n' "Remote commit does not match requested commit." >&2; exit 1; }

# Render the runtime config without exposing the gitignored DB password.
set -a
source "$CONFIG/.env"
set +a
sed -e "s|^\( *\)DB_PASS:.*|\1DB_PASS: \"${DB_ROOT_PASSWORD}\"|" \
    -e "s|^\( *\)HOST:.*|\1HOST: ${PUBLIC_HOST}|" \
  "$APP/config.yaml" > "$CONFIG/config.dev.yaml"
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
sed "s/\[REG_PUBLIC_HOST\]/$REG_PUBLIC_HOST/g" "$APP/ops/Caddyfile.vps" > "$CONFIG/Caddyfile.tmp"
mv "$CONFIG/Caddyfile.tmp" "$CONFIG/Caddyfile"
chmod 644 "$CONFIG/Caddyfile"

"${COMPOSE[@]}" config --quiet < /dev/null
"${COMPOSE[@]}" build maplestory registration < /dev/null
"${COMPOSE[@]}" pull caddy < /dev/null
"${COMPOSE[@]}" up -d db maplestory < /dev/null

ready=0
for _ in $(seq 1 180); do
  if "${COMPOSE[@]}" logs maplestory < /dev/null 2>/dev/null | grep 'Cosmic is now online' >/dev/null; then
    ready=1
    break
  fi
  state="$("${COMPOSE[@]}" ps < /dev/null --format '{{.State}}' maplestory 2>/dev/null || true)"
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

"${COMPOSE[@]}" up -d registration < /dev/null
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

"${COMPOSE[@]}" run --rm --no-deps caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile < /dev/null
"${COMPOSE[@]}" up -d caddy < /dev/null
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
REMOTE

log "Remote dev deploy finished (branch=$BRANCH commit=${COMMIT:0:12}). Check: ops/dev-status.sh"
