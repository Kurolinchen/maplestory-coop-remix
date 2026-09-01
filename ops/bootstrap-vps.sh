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
  mkdir -p "/opt/maple-$env/app" "/opt/maple-$env/config"
  docker network inspect "maple-$env-net" >/dev/null 2>&1 || docker network create "maple-$env-net"
done

# 3. Non-root service user (optional hardening)
id maple >/dev/null 2>&1 || useradd --system --home /opt/maple-dev maple
usermod -aG docker maple || true

# 4. DEV-only secret and Compose configuration. Production stays unconfigured.
if [[ ! -f /opt/maple-dev/config/.env ]]; then
  umask 077
  printf 'DB_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 24)" > /opt/maple-dev/config/.env
fi
chmod 600 /opt/maple-dev/config/.env

cat > /opt/maple-dev/config/docker-compose.yml <<'COMPOSE'
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
      - maple-dev-net
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

volumes:
  maple-dev-db:
    name: maple-dev-db

networks:
  maple-dev-net:
    name: maple-dev-net
    external: true
COMPOSE

# 5. Host firewall: SSH plus MapleStory login/channel ports only.
if ! command -v ufw >/dev/null 2>&1; then
  apt-get update -y
  apt-get install -y ufw
fi
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow OpenSSH >/dev/null
ufw allow 8484/tcp >/dev/null
ufw allow 7575:7577/tcp >/dev/null
ufw --force enable >/dev/null

printf '%s\n' "VPS bootstrap finished."
REMOTE

log "Bootstrap reported success for $VPS_HOST. Next: ops/verify-vps.sh"
