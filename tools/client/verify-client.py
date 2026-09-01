#!/usr/bin/env python3
"""
verify-client.py — validate a prepared MapleStory client directory against its manifest.

Checks:
  * CLIENT_MANIFEST.json exists and parses
  * every manifest file exists with matching size and SHA-256
  * no obsolete upstream files are present (MapleStory.exe, Patcher.exe, ASPLnchr.exe, HShield/)
    — these must be removed by the Cosmic conversion (see docs/CLIENT.md)
  * optionally checks the configured endpoint profile of the manifest

Exit code 0 = verified, 1 = problems found (listed). No writes are performed.

Usage: verify-client.py CLIENT_DIR [--profile NAME]
"""

import argparse
import hashlib
import json
import os
import sys

FORBIDDEN = ["MapleStory.exe", "Patcher.exe", "ASPLnchr.exe", "HShield"]


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description="Verify a prepared client directory against its manifest")
    ap.add_argument("client_dir")
    ap.add_argument("--profile", help="expected endpoint_profile value")
    args = ap.parse_args()

    client_dir = os.path.realpath(args.client_dir)
    problems = []
    manifest_path = os.path.join(client_dir, "CLIENT_MANIFEST.json")

    if not os.path.isdir(client_dir):
        print(f"ERROR: client directory not found: {client_dir}", file=sys.stderr)
        return 1
    if not os.path.isfile(manifest_path):
        print(f"ERROR: manifest not found: {manifest_path}", file=sys.stderr)
        return 1

    manifest = json.load(open(manifest_path, "r", encoding="utf-8"))
    if manifest.get("manifest_schema_version") != 1:
        problems.append("manifest_schema_version is missing or unsupported")
    for key in ("client_package_version", "compatible_server", "cosmic_upstream_client_commit", "endpoint_profile"):
        if not isinstance(manifest.get(key), str) or not manifest[key].strip():
            problems.append(f"manifest field is missing or empty: {key}")
    if not isinstance(manifest.get("files"), list) or not manifest["files"]:
        problems.append("manifest files list is missing or empty")
    print(f"client package version : {manifest.get('client_package_version')}")
    print(f"compatible server      : {manifest.get('compatible_server')}")
    print(f"upstream client commit : {manifest.get('cosmic_upstream_client_commit')}")
    print(f"endpoint profile       : {manifest.get('endpoint_profile')}")

    if args.profile and manifest.get("endpoint_profile") != args.profile:
        problems.append(f"endpoint_profile is '{manifest.get('endpoint_profile')}', expected '{args.profile}'")

    listed_files = set()
    for entry in manifest.get("files", []):
        name = entry["name"]
        listed_files.add(name)
        path = os.path.join(client_dir, name)
        if not os.path.isfile(path):
            problems.append(f"missing file: {name}")
            continue
        size = os.path.getsize(path)
        if size != entry.get("size"):
            problems.append(f"size mismatch: {name} ({size} != {entry.get('size')})")
            continue
        digest = sha256_of(path)
        if digest != entry.get("sha256"):
            problems.append(f"sha256 mismatch: {name}")

    actual_files = set()
    for root, dirs, names in os.walk(client_dir):
        dirs.sort()
        for name in names:
            rel = os.path.relpath(os.path.join(root, name), client_dir).replace(os.sep, "/")
            if rel != "CLIENT_MANIFEST.json":
                actual_files.add(rel)
    for name in sorted(actual_files - listed_files):
        problems.append(f"unlisted extra file: {name}")

    expected_wz = {
        entry["name"]: entry["sha256"]
        for entry in manifest.get("files", [])
        if entry["name"].lower().endswith(".wz")
    }
    if manifest.get("wz_hashes") != expected_wz:
        problems.append("wz_hashes does not match the WZ entries in files")

    for name in FORBIDDEN:
        path = os.path.join(client_dir, name)
        if os.path.exists(path):
            problems.append(f"forbidden file/dir present (must be removed for Cosmic): {name}")

    if problems:
        print("\nVERIFICATION FAILED:")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"\nVERIFIED: {len(manifest.get('files', []))} file(s) OK, no forbidden files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
