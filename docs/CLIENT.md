# Cosmic v83 client

The proprietary MapleStory client, installer and WZ files are never committed. Local files
live below the gitignored `.local/` directory.

## Verified source

- Upstream: `P0nk/Cosmic-client`, branch `main`
- Commit: `6b7328b1593d34a4b134fe6b8a6d20119e526030`
- Committed source hashes: `docs/client/source-manifest-v0.1.0.json`
- Local fetch manifest: `.local/client-source/client-source-manifest.json`
- Prepared local profile: `.local/client-builds/local-dev/MapleStory`
- Endpoint: `127.0.0.1` (login 8484, channels 7575-7577)

The clean v83 installation was converted according to the upstream README: remove
`HShield/`, `ASPLnchr.exe`, `MapleStory.exe` and `Patcher.exe`, then replace the WZ files and
add `HeavenMS-localhost-WINDOW.exe`. `tools/client/verify-client.py` verifies every prepared
file against `CLIENT_MANIFEST.json` and rejects the removed vanilla launcher/anti-cheat
files.

## Linux runtime

The working Pop!_OS configuration is:

- Bottles Flatpak `com.usebottles.bottles`
- Official Bottles runner artifact `soda-11.0-6-x86_64.tar.xz` (SHA-256
  `b3fbd8054fe47ed21de619ee15f8bda340fd0328743d43d42db4e1c5ad561ed8`)
- True 32-bit prefix at `.local/wine-prefixes/maplestory-soda-11.0-6-win32`
- Runtime client at `drive_c/Nexon/MapleStory` inside that prefix

Soda 11.0-6 is required because its release restores true 32-bit prefixes. The previously
tested Soda 11.0-3 win64/new-WoW64 prefix connected to the server but crashed in
`wow64cpu.dll`. Ubuntu Wine 6 with `wine32-preloader` also connected, but loaded
`zlib1.dll` at the client's fixed unpacking address `0x00320000` and recursed into a stack
overflow. Do not add random Winetricks dependencies or patch client binaries to hide these
runtime failures.

### Recreate the Linux runtime

Install the Bottles Flatpak, then download the runner from its official GitHub release and
verify the artifact before extracting it:

```bash
flatpak install flathub com.usebottles.bottles
mkdir -p .local/client-source/runners
mkdir -p "$HOME/.var/app/com.usebottles.bottles/data/bottles/runners"
curl --fail --location \
  --output .local/client-source/runners/soda-11.0-6-x86_64.tar.xz \
  https://github.com/bottlesdevs/wine/releases/download/soda-11.0-6/soda-11.0-6-x86_64.tar.xz
printf '%s  %s\n' \
  b3fbd8054fe47ed21de619ee15f8bda340fd0328743d43d42db4e1c5ad561ed8 \
  .local/client-source/runners/soda-11.0-6-x86_64.tar.xz | sha256sum --check
tar -xJf .local/client-source/runners/soda-11.0-6-x86_64.tar.xz \
  -C "$HOME/.var/app/com.usebottles.bottles/data/bottles/runners"
```

Create a new true 32-bit prefix. Never reuse a win64 prefix for this step:

```bash
mkdir -p .local/wine-prefixes
PREFIX="$PWD/.local/wine-prefixes/maplestory-soda-11.0-6-win32"
RUNNER="$HOME/.var/app/com.usebottles.bottles/data/bottles/runners/soda-11.0-6-x86_64/bin/wineboot"
flatpak run --env=WINEARCH=win32 --env="WINEPREFIX=$PREFIX" \
  --env="MAPLE_WINEBOOT=$RUNNER" --command=sh com.usebottles.bottles \
  -c '"$MAPLE_WINEBOOT" -u'
grep '^#arch=win32$' "$PREFIX/system.reg"
mkdir -p "$PREFIX/drive_c/Nexon"
cp -a .local/client-builds/local-dev/MapleStory "$PREFIX/drive_c/Nexon/"
python3 tools/client/verify-client.py \
  "$PREFIX/drive_c/Nexon/MapleStory" --profile local-dev
```

These commands copy only local, gitignored proprietary assets. They do not add client files
to Git.

Start and inspect the environment with:

```bash
ops/client-status.sh
ops/client-run-local.sh
```

The server must be running first (`ops/start.sh`). Launch logs are written to
`.local/client-logs/`.

## Manual acceptance test

The launcher always validates every runtime client file and checks the login port before launch;
if it is unreachable, it warns and asks before continuing. Process lifetime, the actual
client connection and graphical behavior require observation during the manual checks:

1. Confirm the login screen renders and accepts input.
2. Log in or create the local test account through the supported server flow.
3. Enter character selection, then enter a channel and map.
4. Confirm movement, chat, inventory and NPC interaction work.
5. Exercise the Milestone 0.1 co-op/QoL features listed in `docs/features/0.1-coop-qol.md`.

Stop the client normally from its window. Stop the development server afterward with
`ops/stop.sh` when it is no longer needed.

## Local verification

Verified on Pop!_OS on 2026-08-31:

- client stayed running through login, character creation and Channel 1 entry
- automatic account registration created 15 character slots
- movement, chat, inventory and NPC dialogs worked in a map
- server build passed all 1911 tests before the client run

The broader Milestone 0.1 gameplay checklist in `docs/features/0.1-coop-qol.md` remains a
separate playtest; this verification covers client/runtime compatibility and the basic
login-to-map path.

For GM4 playtest commands, log out, stop only the gameserver and run
`ops/set-dev-gm.sh <character-name>` as described in `docs/TESTING.md`. The helper refuses
to modify an online character or a non-local database.
