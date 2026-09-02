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
for remote_script in ops/_remote-deploy-steps.sh; do
  git -C "$REPO_ROOT" cat-file -e "$COMMIT:$remote_script" 2>/dev/null \
    || die "Deploy scripts missing from commit: $remote_script"
done
log "Deploying branch=$BRANCH commit=${COMMIT:0:12} to $VPS_HOST:/opt/maple-dev"

ssh "$VPS_HOST" bash -s -- "$BRANCH" "$COMMIT" "$PUBLIC_HOST" "$VPS_REG_PUBLIC_ORIGIN" <<'BOOT'
set -euo pipefail
BRANCH="${1:?branch required}"
EXPECTED_COMMIT="${2:?commit required}"
shift 2
APP=/opt/maple-dev/app
REPO_URL="https://github.com/Kurolinchen/maplestory-coop-remix.git"
if [[ ! -d "$APP/.git" ]]; then
  rmdir "$APP" 2>/dev/null || true
  git clone --branch "$BRANCH" --single-branch "$REPO_URL" "$APP"
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
exec bash ops/_remote-deploy-steps.sh "$@"
BOOT

log "Remote dev deploy finished (branch=$BRANCH commit=${COMMIT:0:12}). Check: ops/dev-status.sh"
