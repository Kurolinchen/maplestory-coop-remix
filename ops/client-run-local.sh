#!/usr/bin/env bash
# Launch the local-dev Cosmic client through a true 32-bit Soda prefix.
# Usage: ops/client-run-local.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

BOTTLES_APP="com.usebottles.bottles"
RUNNER_DIR="$HOME/.var/app/com.usebottles.bottles/data/bottles/runners/soda-11.0-6-x86_64"
RUNNER="$RUNNER_DIR/bin/wine"
PREFIX="$REPO_ROOT/.local/wine-prefixes/maplestory-soda-11.0-6-win32"
CLIENT_DIR="$PREFIX/drive_c/Nexon/MapleStory"
LOG_DIR="$REPO_ROOT/.local/client-logs"
[[ "$#" -eq 0 ]] || die "Usage: ops/client-run-local.sh"

flatpak info "$BOTTLES_APP" >/dev/null 2>&1 \
  || die "Bottles Flatpak not installed. Install with: flatpak install flathub com.usebottles.bottles"

[[ -x "$RUNNER" && -d "$RUNNER_DIR/lib/wine/i386-unix" ]] \
  || die "True 32-bit Soda 11.0-6 runner missing. See docs/CLIENT.md."
[[ -f "$PREFIX/system.reg" ]] \
  || die "Client prefix missing: $PREFIX — see docs/CLIENT.md."
grep -q '^#arch=win32$' "$PREFIX/system.reg" \
  || die "Client prefix is not a true 32-bit prefix: $PREFIX"

[[ -f "$CLIENT_DIR/HeavenMS-localhost-WINDOW.exe" ]] \
  || die "Client exe missing from prefix: $CLIENT_DIR — see docs/CLIENT.md."
[[ -d "$CLIENT_DIR" && ! -d "$CLIENT_DIR/HShield" \
  && ! -f "$CLIENT_DIR/ASPLnchr.exe" \
  && ! -f "$CLIENT_DIR/MapleStory.exe" \
  && ! -f "$CLIENT_DIR/Patcher.exe" ]] \
  || die "Client dir still contains vanilla launcher/anti-cheat files; conversion incomplete."

log "Verifying runtime client integrity (full SHA-256 pass)..."
python3 "$REPO_ROOT/tools/client/verify-client.py" "$CLIENT_DIR" --profile local-dev \
  || die "Client verification failed."

if (exec 3<>/dev/tcp/127.0.0.1/8484) 2>/dev/null; then
  exec 3>&- 3<&- || true
  log "Login server reachable on 127.0.0.1:8484."
else
  warn "Login server NOT reachable on 127.0.0.1:8484 — start it with ops/start.sh first."
  confirm "Launch the client anyway?"
fi

mkdir -p "$LOG_DIR"
pgrep -f 'HeavenMS-localhost-WINDOW.exe' >/dev/null 2>&1 \
  && die "Client is already running. Check ops/client-status.sh."
TS="$(date +%Y%m%d-%H%M%S)"
LAUNCH_LOG="$LOG_DIR/launch-$TS.log"
log "Launching client through Soda 11.0-6 true win32 prefix (log: $LAUNCH_LOG)..."
setsid nohup flatpak run \
  --env=WINEARCH=win32 \
  --env="WINEPREFIX=$PREFIX" \
  --env=WINEDEBUG=-all \
  --env="MAPLE_CLIENT_DIR=$CLIENT_DIR" \
  --env="MAPLE_WINE_RUNNER=$RUNNER" \
  --command=sh "$BOTTLES_APP" \
  -c 'cd "$MAPLE_CLIENT_DIR" && "$MAPLE_WINE_RUNNER" HeavenMS-localhost-WINDOW.exe' \
  > "$LAUNCH_LOG" 2>&1 < /dev/null &
log "Launch initiated (pid $!). If nothing appears within ~30s, check $LAUNCH_LOG and ops/logs.sh server."
