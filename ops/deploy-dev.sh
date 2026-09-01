#!/usr/bin/env bash
# Deploy the DEV instance to the VPS. Requires owner approval.
# Production deployment is a separate, always-human-approved process (not in this script).
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
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

ssh "$VPS_HOST" bash -s -- "$BRANCH" "$COMMIT" "$PUBLIC_HOST" <<'REMOTE'
set -euo pipefail
BRANCH="${1:-development}"
EXPECTED_COMMIT="${2:?expected commit required}"
PUBLIC_HOST="${3:?public host required}"
APP=/opt/maple-dev/app
CONFIG=/opt/maple-dev/config

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

docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env" up -d --build
docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env" ps

ready=0
for _ in $(seq 1 180); do
  if docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env" \
      logs maplestory 2>/dev/null | grep 'Cosmic is now online' >/dev/null; then
    ready=1
    break
  fi
  state="$(docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env" \
      ps --format '{{.State}}' maplestory 2>/dev/null || true)"
  [[ "$state" != "exited" && "$state" != "dead" ]] || break
  sleep 1
done
[[ "$ready" == 1 ]] || {
  docker compose -f "$CONFIG/docker-compose.yml" --env-file "$CONFIG/.env" \
    logs --tail=100 maplestory >&2 || true
  printf '%s\n' "Server did not become ready." >&2
  exit 1
}
for port in 8484 7575 7576 7577; do
  (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null \
    || { printf 'Port %s is not reachable.\n' "$port" >&2; exit 1; }
  exec 3>&- 3<&-
done
REMOTE

log "Remote dev deploy finished (branch=$BRANCH commit=${COMMIT:0:12}). Check: ops/dev-status.sh"
