# Client versioning

Client binaries and WZ files remain under the gitignored `.local/` workspace. Git contains
only tools, documentation, schemas and hash metadata.

## Source manifests

`source-manifest-v0.1.0.json` pins the exact `P0nk/Cosmic-client` revision and records the
name, byte size and SHA-256 digest of every upstream artifact used to build the client.
Committed source manifests are immutable. If an upstream executable, installer or WZ file
changes, add a new versioned source manifest instead of editing an existing one.

## Prepared package manifests

Every prepared client directory contains a local `CLIENT_MANIFEST.json` conforming to
`client-manifest.schema.json`. `manifest_schema_version` changes only for incompatible
metadata-shape changes. `client_package_version` changes whenever executable/WZ content or
its server compatibility changes.

Endpoint-only builds may share a package version. `endpoint_profile` and the patched
executable digest distinguish `local-dev`, `vps-dev` and `vps-prod` packages. The
`wz_hashes` map duplicates the WZ entries from `files` for quick review; the verifier
requires both representations to match.

`compatible_server` records the intended server milestone and baseline. For package 0.1.0
the supported target is `milestone-0.1 (Cosmic v1.1.3 baseline)`. Future releases must use
an explicit milestone/range rather than `latest` or `unknown`.

Generate and verify local manifests with:

```bash
python3 tools/client/make-manifest.py CLIENT_DIR \
  --version 0.1.0 \
  --server-compat "milestone-0.1 (Cosmic v1.1.3 baseline)" \
  --upstream-commit 6b7328b1593d34a4b134fe6b8a6d20119e526030 \
  --profile local-dev
python3 tools/client/verify-client.py CLIENT_DIR --profile local-dev
```
