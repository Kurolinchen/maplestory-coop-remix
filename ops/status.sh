#!/usr/bin/env bash
# Dev environment status: containers, volume, backups, git. Usage: ops/status.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

echo "== Git =="
git -C "$REPO_ROOT" status --short --branch || true
git -C "$REPO_ROOT" log --oneline -3 || true

echo
echo "== Docker containers =="
ensure_env >/dev/null
compose ps || true

echo
echo "== DB volume =="
docker_cli volume ls --filter "name=${COMPOSE_PROJECT}" || true

echo
echo "== Backups (latest 5) =="
if [[ -d "$BACKUP_DIR" ]]; then
  ls -1t "$BACKUP_DIR" 2>/dev/null | head -n5 || echo "(none)"
else
  echo "(none)"
fi

echo
echo "== Rendered config =="
if [[ -f "$CONFIG_DIR/config.dev.yaml" ]]; then
  echo "present: $CONFIG_DIR/config.dev.yaml"
else
  echo "not rendered yet (ops/start.sh does this)"
fi
