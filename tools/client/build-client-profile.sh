#!/usr/bin/env bash
# build-client-profile.sh — build an endpoint-specific client package from the verified
# local-dev template, patching the login-server IP where needed.
#
#   Usage: build-client-profile.sh <profile> [template-dir]
#          profile: local-dev | vps-dev | vps-prod
#
# Behavior:
#   * Reads endpoint IPs from tools/client/profiles.env (IPs only, never secrets).
#   * local-dev (127.0.0.1): the stock Cosmic client already targets localhost, so no
#     binary patching is done — the template is verified in place.
#   * any other profile: copies the template to .local/client-builds/<profile>/MapleStory and patches
#     HeavenMS-localhost-WINDOW.exe via tools/client/patch-client-ip.py (which refuses
#     instead of corrupting if the layout is unexpected). Regenerates the manifest.
#   * Refuses if the profile IP is empty (not configured yet) or the template is missing.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS="$REPO_ROOT/tools/client"
BUILD_ROOT="$REPO_ROOT/.local/client-builds"
CLIENT_EXE="HeavenMS-localhost-WINDOW.exe"

profile="${1:-}"
template="${2:-$BUILD_ROOT/local-dev/MapleStory}"
[[ "$#" -ge 1 && "$#" -le 2 ]] || { echo "Usage: $0 <profile> [template-dir]" >&2; exit 2; }

# shellcheck disable=SC1091
source "$TOOLS/profiles.env"
case "$profile" in
  local-dev) ip="$PROFILE_local_dev_ip" ;;
  vps-dev) ip="$PROFILE_vps_dev_ip" ;;
  vps-prod) ip="$PROFILE_vps_prod_ip" ;;
  *) echo "ERROR: unsupported profile '$profile' (expected local-dev, vps-dev or vps-prod)" >&2; exit 2 ;;
esac

[[ -d "$template" ]] || { echo "ERROR: template dir not found: $template" >&2; exit 1; }
template="$(realpath -e "$template")"
[[ -f "$template/$CLIENT_EXE" ]] || { echo "ERROR: $CLIENT_EXE not in template: $template" >&2; exit 1; }
[[ -f "$template/CLIENT_MANIFEST.json" ]] || { echo "ERROR: CLIENT_MANIFEST.json not in template: $template" >&2; exit 1; }
[[ -n "$ip" ]] || { echo "ERROR: profile '$profile' has no IP configured in profiles.env; refusing" >&2; exit 1; }
python3 - "$ip" <<'PY'
import ipaddress
import sys

try:
    parsed = ipaddress.IPv4Address(sys.argv[1])
except ipaddress.AddressValueError as exc:
    raise SystemExit(f"ERROR: invalid IPv4 endpoint: {exc}")
if str(parsed) != sys.argv[1]:
    raise SystemExit("ERROR: endpoint must use canonical dotted-quad IPv4 notation")
PY
[[ "$profile" != "local-dev" || "$ip" == "127.0.0.1" ]] \
  || { echo "ERROR: local-dev must target 127.0.0.1" >&2; exit 1; }

echo "profile : $profile"
echo "template: $template"
python3 "$TOOLS/verify-client.py" "$template" --profile local-dev

if [[ "$profile" == "local-dev" ]]; then
  echo "local-dev targets 127.0.0.1 — verifying template in place (no patch needed)"
  python3 "$TOOLS/patch-client-ip.py" "$template/$CLIENT_EXE" 127.0.0.1 \
    "$template/$CLIENT_EXE.patchcheck" --dry-run
  exit 0
fi

out="$BUILD_ROOT/$profile/MapleStory"
[[ -e "$out" ]] && { echo "ERROR: output already exists: $out (remove it first)" >&2; exit 1; }
mkdir -p "$(dirname "$out")"

echo "copying template -> $out"
cp -a "$template" "$out"

echo "patching $CLIENT_EXE to $ip"
python3 "$TOOLS/patch-client-ip.py" "$out/$CLIENT_EXE" "$ip" "$out/$CLIENT_EXE.new"
mv "$out/$CLIENT_EXE.new" "$out/$CLIENT_EXE"

# Refresh manifest to reflect the patched exe and the profile.
metadata="$(python3 - "$template/CLIENT_MANIFEST.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    manifest = json.load(stream)
keys = ("client_package_version", "compatible_server", "cosmic_upstream_client_commit")
values = [manifest.get(key) for key in keys]
if not all(isinstance(value, str) and value.strip() for value in values):
    raise SystemExit("ERROR: template manifest has missing package metadata")
print("\t".join(values))
PY
)"
IFS=$'\t' read -r version server_compat upstream_commit <<< "$metadata"
python3 "$TOOLS/make-manifest.py" "$out" \
  --version "$version" \
  --server-compat "$server_compat" \
  --upstream-commit "$upstream_commit" \
  --profile "$profile"

python3 "$TOOLS/verify-client.py" "$out" --profile "$profile"
echo "DONE: $out"
