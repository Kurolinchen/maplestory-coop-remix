#!/usr/bin/env bash
# Status report for the local client environment (no secrets printed).
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

[[ "$#" -eq 0 ]] || die "Usage: ops/client-status.sh"

BOTTLES_APP="com.usebottles.bottles"
RUNNER_DIR="$HOME/.var/app/com.usebottles.bottles/data/bottles/runners/soda-11.0-6-x86_64"
PREFIX="$REPO_ROOT/.local/wine-prefixes/maplestory-soda-11.0-6-win32"
CLIENT_DIR="$PREFIX/drive_c/Nexon/MapleStory"
TEMPLATE_DIR="$REPO_ROOT/.local/client-builds/local-dev/MapleStory"
status=0

echo "== Linux runner =="
if flatpak info "$BOTTLES_APP" >/dev/null 2>&1; then
  echo "Bottles Flatpak: present"
else
  echo "Bottles Flatpak: MISSING"
fi
if [[ -x "$RUNNER_DIR/bin/wine" && -d "$RUNNER_DIR/lib/wine/i386-unix" ]]; then
  echo "Soda 11.0-6: present (true 32-bit runtime available)"
else
  echo "Soda 11.0-6: MISSING or lacks i386-unix"
fi
if [[ -f "$PREFIX/system.reg" ]] && grep -q '^#arch=win32$' "$PREFIX/system.reg"; then
  echo "win32 prefix: present (architecture verified)"
else
  echo "win32 prefix: MISSING or wrong architecture"
fi

echo "== Client files =="
if [[ -f "$CLIENT_DIR/HeavenMS-localhost-WINDOW.exe" ]]; then
  echo "runtime copy: present ($CLIENT_DIR)"
  [[ -d "$CLIENT_DIR/HShield" \
    || -f "$CLIENT_DIR/ASPLnchr.exe" \
    || -f "$CLIENT_DIR/MapleStory.exe" \
    || -f "$CLIENT_DIR/Patcher.exe" ]] \
    && echo "  WARNING: vanilla launcher/anti-cheat files still present" \
    || echo "  conversion: clean (no HShield/MapleStory.exe/Patcher.exe/ASPLnchr.exe)"
else
  echo "runtime copy: MISSING"
  status=1
fi
if [[ -f "$CLIENT_DIR/CLIENT_MANIFEST.json" ]]; then
  echo "runtime verification: checking all manifest hashes..."
  if python3 "$REPO_ROOT/tools/client/verify-client.py" "$CLIENT_DIR" --profile local-dev; then
    echo "runtime verification: PASSED"
  else
    echo "runtime verification: FAILED"
    status=1
  fi
else
  echo "runtime verification: FAILED (manifest missing)"
  status=1
fi
if [[ -f "$TEMPLATE_DIR/CLIENT_MANIFEST.json" ]]; then
  python3 - "$TEMPLATE_DIR" <<'EOF'
import json, sys
m = json.load(open(sys.argv[1] + "/CLIENT_MANIFEST.json"))
print(f"host template: present — package v{m['client_package_version']}, "
      f"profile {m['endpoint_profile']}, {len(m['files'])} files, "
      f"upstream client commit {m['cosmic_upstream_client_commit'][:12]}")
EOF
else
  echo "host template: MISSING ($TEMPLATE_DIR)"
fi
if pgrep -f 'HeavenMS-localhost-WINDOW.exe' >/dev/null 2>&1; then
  echo "client process: running"
else
  echo "client process: not running"
fi

echo "== Server =="
if (exec 3<>/dev/tcp/127.0.0.1/8484) 2>/dev/null; then
  exec 3>&- 3<&- || true
  echo "login port 8484: reachable"
else
  echo "login port 8484: NOT reachable (ops/start.sh)"
  status=1
fi
compose ps 2>/dev/null | sed 's/^/  /' || true
exit "$status"
