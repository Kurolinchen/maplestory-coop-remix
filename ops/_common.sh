#!/usr/bin/env bash
# Common helpers for all ops scripts. Sourced, not executed.
set -euo pipefail

OPS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$OPS_DIR/.." && pwd)"
COMPOSE_FILE="$OPS_DIR/docker-compose.dev.yml"
ENV_FILE="$OPS_DIR/.env"
ENV_EXAMPLE="$OPS_DIR/.env.example"
CONFIG_DIR="$OPS_DIR/config"
BACKUP_DIR="$OPS_DIR/backups"
COMPOSE_PROJECT="maple-coop-dev"
DB_SERVICE="db"
SERVER_SERVICE="maplestory"
ONLINE_MARKER="Cosmic is now online"
VPS_ENV_FILE="$OPS_DIR/vps.env"

# Optional VPS endpoint (gitignored). Sourced here so every remote script sees
# VPS_HOST without each caller passing it. Contains host/alias only, no secrets.
if [[ -f "$VPS_ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$VPS_ENV_FILE"
  set +a
fi

log()  { printf '\033[1;34m[ops]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[ops][warn]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[ops][error]\033[0m %s\n' "$*" >&2; exit 1; }

resolve_java() {
  if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" ]]; then
    local sdkman_home="${SDKMAN_DIR:-$HOME/.sdkman}"
    local candidate=""
    if [[ -d "$sdkman_home/candidates/java" ]]; then
      candidate="$(ls -d "$sdkman_home/candidates/java/"21* 2>/dev/null | head -n1 || true)"
      [[ -z "$candidate" ]] && candidate="$sdkman_home/candidates/java/current"
    fi
    [[ -n "$candidate" && -x "$candidate/bin/java" ]] \
      || die "Java 21 not found. Install via: sdk install java 21.0.12-amzn (docs/DECISIONS.md D4)."
    export JAVA_HOME="$candidate"
  fi
  if ! "$JAVA_HOME/bin/java" -version 2>&1 | head -n1 | grep -q ' "21'; then
    warn "JAVA_HOME=$JAVA_HOME does not look like Java 21."
  fi
  export PATH="$JAVA_HOME/bin:$PATH"
}

ensure_env() {
  if [[ ! -f "$ENV_FILE" ]]; then
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    local pw
    pw="$(openssl rand -hex 24)"
    sed -i "s/^DB_ROOT_PASSWORD=.*/DB_ROOT_PASSWORD=$pw/" "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    log "Created $ENV_FILE with a generated DB password (chmod 600, gitignored)."
  fi
}

load_env() {
  [[ -f "$ENV_FILE" ]] || die "$ENV_FILE missing - run ops/start.sh once first."
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  [[ -n "${DB_ROOT_PASSWORD:-}" && "$DB_ROOT_PASSWORD" != "change-me-strong-password" ]] \
    || die "DB_ROOT_PASSWORD is unset or still the example value."
}

# Render a dev config.yaml with the real DB password injected (kept out of git).
render_config() {
  mkdir -p "$CONFIG_DIR"
  local out="$CONFIG_DIR/config.dev.yaml"
  sed "s|^\( *\)DB_PASS:.*|\1DB_PASS: \"${DB_ROOT_PASSWORD}\"|" "$REPO_ROOT/config.yaml" > "$out"
  log "Rendered $out from config.yaml."
}

# docker CLI wrapper: works even if the current session lacks the docker group.
# Arguments are re-quoted with %q so values containing spaces/quotes survive sg's shell.
docker_cli() {
  if docker info >/dev/null 2>&1; then
    docker "$@"
  elif command -v sg >/dev/null 2>&1 && sg docker -c "docker info" >/dev/null 2>&1; then
    local quoted="" arg
    for arg in "$@"; do
      quoted+=" $(printf '%q' "$arg")"
    done
    sg docker -c "docker${quoted}"
  else
    die "Docker is not usable in this session. Start the daemon / re-login for the docker group."
  fi
}

compose() {
  docker_cli compose --project-name "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

confirm() {
  local prompt="$1" reply
  read -r -p "$prompt [y/N] " reply || reply=""
  [[ "$reply" =~ ^[Yy]([Ee][Ss])?$ ]] || die "Aborted."
}
