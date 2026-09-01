# Client tooling (`tools/client/`)

Committable tooling + metadata for the MapleStory v83 / Cosmic client. **No proprietary
assets live here or in Git** — binaries, WZ files and installers stay in the gitignored
`.local/` workspace (see `docs/CLIENT.md`).

## Files

| File | Purpose |
|---|---|
| `patch-client-ip.py` | Safe login-server IP patcher for the client executable |
| `verify-client.py` | Verify a prepared client dir against its `CLIENT_MANIFEST.json` |
| `make-manifest.py` | Generate `CLIENT_MANIFEST.json` (names, sizes, SHA-256 only) |
| `build-client-profile.sh` | Build endpoint-specific client packages (local-dev/vps-dev/vps-prod) |
| `profiles.env` | Endpoint IPs per profile (IPs only — never secrets) |

## patch-client-ip and the three localhost occurrences

Upstream (Cosmic-client README, "Edit client ip") documents that the client executable
contains the string `127.0.0.1` **three times, right above each other**, and suggests
overwriting them by hand in a hex editor. Verified on the official
`HeavenMS-localhost-WINDOW.exe` (Cosmic-client commit `6b7328b`):

- 3 occurrences at `0x6fe084`, `0x6fe094`, `0x6fe0a4` — uniform 16-byte spacing
- each occurrence sits in a fixed **16-byte field**: 9 ASCII bytes + 7 NUL padding
- max IPv4 dotted string (`255.255.255.255`, 15 chars) + NUL = 16 bytes → fits exactly

`patch-client-ip.py` automates this safely instead of blind byte replacement:

1. validates the target as dotted-quad IPv4 (IPv4 only; the v83 client has no IPv6 support)
2. refuses to write in-place — input is never modified, output must be a different file
3. requires exactly the expected occurrence count (default 3, `--expected-count`)
4. derives the field length from the spacing between occurrences, requires uniform spacing
   and all-NUL padding; any deviation aborts without writing
5. refuses if the new IP + NUL does not fit the field
6. re-verifies the result in memory (size unchanged, old IP gone, new IP count correct)
   before writing, then verifies the written file and reports SHA-256 before/after

```
python3 tools/client/patch-client-ip.py --self-test
python3 tools/client/patch-client-ip.py IN.exe 1.2.3.4 OUT.exe --dry-run
```

## Profiles

```
tools/client/build-client-profile.sh local-dev     # verify .local/client-builds/local-dev/MapleStory
tools/client/build-client-profile.sh vps-dev       # build .local/client-builds/vps-dev/MapleStory
```

## Manifest / versioning

Every prepared client directory carries a `CLIENT_MANIFEST.json`:

```json
{
  "manifest_schema_version": 1,
  "client_package_version": "0.1.0",
  "compatible_server": "milestone-0.1 (Cosmic v1.1.3 baseline)",
  "cosmic_upstream_client_commit": "6b7328b...",
  "endpoint_profile": "local-dev",
  "wz_hashes": {"Character.wz": "..."},
  "files": [{ "name": "...", "size": 123, "sha256": "..." }]
}
```

Rule for the future: whenever WZ/exe content changes, bump `client_package_version` and
record the compatible server range — that is how "Server v0.4 requires Client v0.2" stays
answerable. The schema and immutable source hashes live under `docs/client/`. Only
manifests/tooling/docs are committed; never binaries.

## Linux runtime

The tested Pop!_OS launcher is `ops/client-run-local.sh`. It uses the official Bottles
`soda-11.0-6` runner in a true 32-bit prefix. Older Soda 11 builds use new WoW64 and crash
this legacy client in `wow64cpu.dll`; distro Wine 6 also collides with the client's fixed
low-memory unpacking area. Setup and troubleshooting details are in `docs/CLIENT.md`.
