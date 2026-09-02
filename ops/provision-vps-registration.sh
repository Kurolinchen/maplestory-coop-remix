#!/usr/bin/env bash
# Provision missing DEV registration secrets on the VPS without printing them.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

[[ "$-" != *x* ]] || die "Refusing to run while shell tracing is enabled."
VPS_HOST="${VPS_HOST:-}"
[[ -n "$VPS_HOST" ]] || die "VPS_HOST not set - remote operations are not configured yet."
[[ "${1:-}" == "--i-understand" ]] || die "Refusing: requires '--i-understand' AND explicit owner approval."

ssh "$VPS_HOST" bash -s <<'REMOTE'
set -euo pipefail
[[ "$-" != *x* ]] || { printf '%s\n' "Refusing under shell tracing." >&2; exit 1; }

secret_dir=/opt/maple-dev/secrets
install -d -m 700 -o root -g root "$secret_dir"

create_secret() {
  local target="$1" tmp
  [[ ! -e "$target" ]] || {
    [[ -s "$target" ]] || { printf 'Existing secret is empty: %s\n' "$target" >&2; exit 1; }
    chown 10001:10001 "$target"
    chmod 400 "$target"
    return
  }
  tmp="$(mktemp "$secret_dir/.secret.XXXXXX")"
  openssl rand -base64 32 | tr -d '\n' > "$tmp"
  printf '\n' >> "$tmp"
  chown 10001:10001 "$tmp"
  chmod 400 "$tmp"
  mv "$tmp" "$target"
}

create_secret "$secret_dir/reg_db_password"
create_secret "$secret_dir/reg_invite_passphrase"
printf '%s\n' "DEV registration secrets are present (contents not displayed)."
REMOTE

log "Registration secrets provisioned on $VPS_HOST."
