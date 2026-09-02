#!/usr/bin/env bash
# Bootstraps the MapleStory VPS layout (idempotent). Requires owner approval.
# Layout: /opt/maple-dev and /opt/maple-prod fully isolated (dirs, compose projects,
# volumes, networks, config, secrets). No OpenCode / no LLM keys on the VPS. Ever.
# See docs/DEPLOYMENT.md. Run only with owner approval.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
[[ "${1:-}" == "--i-understand" ]] || die "Refusing: requires '--i-understand' AND explicit owner approval."

ssh "$VPS_HOST" bash -s <<'REMOTE'
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

# 1. Docker (skip if present)
if ! command -v docker >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y docker.io docker-compose-v2
  systemctl enable --now docker
fi

# 2. Isolated layouts for dev and prod
for env in dev prod; do
  mkdir -p "/opt/maple-$env/app" "/opt/maple-$env/config" "/opt/maple-$env/secrets"
  chmod 700 "/opt/maple-$env/secrets"
  docker network inspect "maple-$env-net" >/dev/null 2>&1 || docker network create "maple-$env-net"
done

ensure_network() {
  local name="$1" subnet="$2" internal="$3" actual_subnet actual_internal
  if docker network inspect "$name" >/dev/null 2>&1; then
    actual_subnet="$(docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' "$name")"
    actual_internal="$(docker network inspect -f '{{.Internal}}' "$name")"
    [[ "$actual_subnet" == "$subnet" && "$actual_internal" == "$internal" ]] || {
      printf 'Network %s exists with unexpected configuration; refusing.\n' "$name" >&2
      exit 1
    }
    return
  fi
  if [[ "$internal" == true ]]; then
    docker network create --internal --subnet "$subnet" "$name" >/dev/null
  else
    docker network create --subnet "$subnet" "$name" >/dev/null
  fi
}
ensure_network maple-dev-web-net 172.30.250.0/24 false
ensure_network maple-dev-registration-db-net 172.30.251.0/29 true

# 3. Non-root service user (optional hardening)
id maple >/dev/null 2>&1 || useradd --system --home /opt/maple-dev maple
usermod -aG docker maple || true

# 4. DEV-only secret and Compose configuration. Production stays unconfigured.
if [[ ! -f /opt/maple-dev/config/.env ]]; then
  umask 077
  printf 'DB_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 24)" > /opt/maple-dev/config/.env
fi
chmod 600 /opt/maple-dev/config/.env

cat > /opt/maple-dev/config/docker-compose.yml.tmp <<'COMPOSE'
name: maple-dev
services:
  db:
    image: mysql:8.4.0
    environment:
      MYSQL_DATABASE: cosmic
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD:?set DB_ROOT_PASSWORD}
    volumes:
      - maple-dev-db:/var/lib/mysql
    networks:
      maple-dev-net: {}
      maple-dev-registration-db-net:
        ipv4_address: 172.30.251.2
    healthcheck:
      test: ["CMD-SHELL", "mysql -h 127.0.0.1 -uroot -p\"$$MYSQL_ROOT_PASSWORD\" -e 'SELECT 1' >/dev/null 2>&1"]
      interval: 5s
      timeout: 5s
      retries: 30
      start_period: 30s

  maplestory:
    build:
      context: ../app
      dockerfile: Dockerfile
    depends_on:
      db:
        condition: service_healthy
    ports:
      - "8484:8484"
      - "7575-7577:7575-7577"
    volumes:
      - ./config.dev.yaml:/opt/server/config.yaml:ro
      - ../app/scripts:/opt/server/scripts:ro
      - ../app/wz:/opt/server/wz:ro
    environment:
      DB_HOST: db
    networks:
      - maple-dev-net

  registration:
    build:
      context: ../app
      dockerfile: Dockerfile.registration
    depends_on:
      db:
        condition: service_healthy
    env_file:
      - ./registration.env
    volumes:
      - ../secrets/reg_db_password:/run/secrets/reg_db_password:ro
      - ../secrets/reg_invite_passphrase:/run/secrets/reg_invite_passphrase:ro
    read_only: true
    tmpfs:
      - /tmp:size=16m,mode=1777
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    restart: unless-stopped
    networks:
      maple-dev-web-net:
        ipv4_address: 172.30.250.3
      maple-dev-registration-db-net:
        ipv4_address: 172.30.251.3

volumes:
  maple-dev-db:
    name: maple-dev-db

networks:
  maple-dev-net:
    name: maple-dev-net
    external: true
  maple-dev-web-net:
    name: maple-dev-web-net
    external: true
  maple-dev-registration-db-net:
    name: maple-dev-registration-db-net
    external: true
COMPOSE
mv /opt/maple-dev/config/docker-compose.yml.tmp /opt/maple-dev/config/docker-compose.yml

# 5. Host firewall: SSH, MapleStory and the shared Caddy TLS edge (cookwiki) only.
if ! command -v ufw >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y ufw
fi
if ! command -v curl >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y curl
fi
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow OpenSSH >/dev/null
ufw allow 8484/tcp >/dev/null
ufw allow 7575:7577/tcp >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw --force enable >/dev/null

printf '%s\n' "VPS bootstrap finished."
REMOTE

log "Bootstrap reported success for $VPS_HOST. Next: ops/verify-vps.sh"
