#!/usr/bin/env bash
# Read-only readiness checks for the VPS.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
VPS_REG_PUBLIC_ORIGIN="${VPS_REG_PUBLIC_ORIGIN:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
[[ "$VPS_REG_PUBLIC_ORIGIN" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] \
  || die "VPS_REG_PUBLIC_ORIGIN must be a configured https hostname origin."

log "Checking $VPS_HOST ..."
ssh -o BatchMode=yes -o ConnectTimeout=10 "$VPS_HOST" bash -s -- "$VPS_REG_PUBLIC_ORIGIN" <<'REMOTE'
set -euo pipefail
REG_PUBLIC_ORIGIN="${1:?registration public origin required}"
fail() { printf 'FAILED: %s\n' "$1" >&2; exit 1; }
check_network() {
  local name="$1" subnet="$2" internal="$3"
  [[ "$(docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' "$name" 2>/dev/null)" == "$subnet" ]] || fail "$name subnet"
  [[ "$(docker network inspect -f '{{.Internal}}' "$name" 2>/dev/null)" == "$internal" ]] || fail "$name internal flag"
}
echo "os:       $(. /etc/os-release && echo "$PRETTY_NAME")"
echo "docker:   $(docker --version 2>/dev/null || echo MISSING)"
echo "compose:  $(docker compose version 2>/dev/null || echo MISSING)"
echo "dev dir:  $(test -d /opt/maple-dev && echo present || echo MISSING)"
echo "dev app:  $(test -d /opt/maple-dev/app && echo present || echo MISSING)"
echo "dev cfg:  $(test -f /opt/maple-dev/config/docker-compose.yml && test -f /opt/maple-dev/config/.env && echo present || echo MISSING)"
echo "prod dir: $(test -d /opt/maple-prod && echo present || echo MISSING)"
echo "dev net:  $(docker network inspect maple-dev-net >/dev/null 2>&1 && echo present || echo MISSING)"
echo "prod net: $(docker network inspect maple-prod-net >/dev/null 2>&1 && echo present || echo MISSING)"
echo "dev containers:";  docker ps --filter name=maple-dev  --format '  {{.Names}} {{.Status}}' || true
echo "prod containers:"; docker ps --filter name=maple-prod --format '  {{.Names}} {{.Status}}' || true
echo "opencode present (must be NO): $(command -v opencode >/dev/null 2>&1 && echo 'YES - VIOLATION' || echo no)"

check_network maple-dev-web-net 172.30.250.0/24 false
check_network maple-dev-registration-db-net 172.30.251.0/29 true
cd /opt/maple-dev/app
compose=(docker compose -f ../config/docker-compose.yml --env-file ../config/.env)
for service in db maplestory registration; do
  [[ "$("${compose[@]}" ps --status running -q "$service")" ]] || fail "$service is not running"
done
registration_id="$("${compose[@]}" ps -q registration)"
[[ "$(docker inspect -f '{{.State.Health.Status}}' "$registration_id")" == healthy ]] || fail "registration is not healthy"
[[ "$(docker inspect -f '{{with index .NetworkSettings.Networks "maple-dev-web-net"}}{{.IPAddress}}{{end}}' "$registration_id")" == 172.30.250.3 ]] || fail "registration web IP"
[[ "$(docker inspect -f '{{with index .NetworkSettings.Networks "maple-dev-registration-db-net"}}{{.IPAddress}}{{end}}' "$registration_id")" == 172.30.251.3 ]] || fail "registration DB IP"
[[ -z "$("${compose[@]}" port db 3306 2>/dev/null)" ]] || fail "DB port is published"
[[ -z "$("${compose[@]}" port registration 8080 2>/dev/null)" ]] || fail "registration port is published"
[[ "$(docker inspect -f '{{.Name}}' cookwiki-caddy-1 2>/dev/null)" == "/cookwiki-caddy-1" ]] || fail "shared edge container missing"
[[ "$(docker inspect -f '{{with index .NetworkSettings.Networks "maple-dev-web-net"}}{{.IPAddress}}{{end}}' cookwiki-caddy-1)" == 172.30.250.2 ]] || fail "shared edge web-net IP"
[[ "$(sudo docker exec cookwiki-caddy-1 grep -c 'maple-dev-registration site' /etc/caddy/Caddyfile 2>/dev/null || echo 0)" -ge 1 ]] || fail "edge has no maple site block"
for secret in ../secrets/reg_db_password ../secrets/reg_invite_passphrase; do
  [[ -s "$secret" ]] || fail "missing registration secret"
  [[ "$(stat -c '%a:%u:%g' "$secret")" == 400:10001:10001 ]] || fail "registration secret permissions"
done
grant_state="$("${compose[@]}" exec -T db sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B -e "
     SELECT COUNT(*) FROM mysql.user WHERE User = \"registration\" AND Host = \"172.30.251.3\";
     SELECT COUNT(*) FROM mysql.tables_priv WHERE User = \"registration\" AND Host = \"172.30.251.3\"
       AND Db = \"cosmic\" AND Table_name = \"accounts\" AND Table_priv = \"Insert\" AND Column_priv = \"\";
     SELECT COUNT(*) FROM mysql.db WHERE User = \"registration\";
     SELECT COUNT(*) FROM mysql.user WHERE User = \"registration\";"' | paste -sd ' ' -)"
[[ "$grant_state" == "1 1 0 1" ]] || fail "registration DB grants"
[[ "$(curl -fsS "$REG_PUBLIC_ORIGIN/health/ready")" == ready ]] || fail "HTTPS readiness"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "http://${REG_PUBLIC_ORIGIN#https://}/register")" == 308 ]] || fail "HTTP redirect"
[[ "$(curl -sS -o /dev/null -w '%{http_code}' "$REG_PUBLIC_ORIGIN/handbook.pdf")" == 404 ]] || fail "handbook route"
headers="$(curl -fsSI "$REG_PUBLIC_ORIGIN/")"
grep -qi '^content-security-policy:' <<<"$headers" || fail "CSP header"
grep -qi '^x-content-type-options: nosniff' <<<"$headers" || fail "nosniff header"
grep -qi '^referrer-policy: no-referrer' <<<"$headers" || fail "referrer policy"
! grep -qi '^strict-transport-security:' <<<"$headers" || fail "unexpected HSTS header"
[[ -z "$(docker ps -q --filter name=maple-prod)" ]] || fail "production containers are running"
printf '%s\n' "DEV game and registration verification passed."
REMOTE
log "Verification done."
