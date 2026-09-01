#!/usr/bin/env python3
"""
make-manifest.py — generate CLIENT_MANIFEST.json for a prepared client directory.

Hashes every file in the directory tree (except the manifest itself) and records
package metadata (version, compatible server, upstream client commit, endpoint profile).
No proprietary data leaves the machine: the manifest contains names, sizes, SHA-256 only.

Usage:
  make-manifest.py CLIENT_DIR --version 0.1.0 --server-compat "milestone-0.1 (Cosmic v1.1.3)"
                   --upstream-commit <sha> --profile local-dev
"""

import argparse
import hashlib
import json
import os
import sys

MANIFEST_NAME = "CLIENT_MANIFEST.json"


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate CLIENT_MANIFEST.json")
    ap.add_argument("client_dir")
    ap.add_argument("--version", required=True, dest="version")
    ap.add_argument("--server-compat", required=True)
    ap.add_argument("--upstream-commit", required=True)
    ap.add_argument("--profile", required=True)
    args = ap.parse_args()

    client_dir = os.path.realpath(args.client_dir)
    if not os.path.isdir(client_dir):
        print(f"ERROR: not a directory: {client_dir}", file=sys.stderr)
        return 1

    files = []
    for root, dirs, names in os.walk(client_dir):
        dirs.sort()
        for name in sorted(names):
            if root == client_dir and name == MANIFEST_NAME:
                continue
            path = os.path.join(root, name)
            rel = os.path.relpath(path, client_dir).replace(os.sep, "/")
            files.append({"name": rel, "size": os.path.getsize(path), "sha256": sha256_of(path)})

    manifest = {
        "manifest_schema_version": 1,
        "client_package_version": args.version,
        "compatible_server": args.server_compat,
        "cosmic_upstream_client_commit": args.upstream_commit,
        "endpoint_profile": args.profile,
        "wz_hashes": {
            entry["name"]: entry["sha256"]
            for entry in files
            if entry["name"].lower().endswith(".wz")
        },
        "files": files,
    }
    out = os.path.join(client_dir, MANIFEST_NAME)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")
    print(f"wrote {out}: {len(files)} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
