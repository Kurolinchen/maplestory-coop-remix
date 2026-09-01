#!/usr/bin/env python3
"""
patch-client-ip.py — safe server-IP patcher for the Cosmic/HeavenMS v83 client executable.

Background (docs/CLIENT.md, Cosmic-client README "Edit client ip"):
  The client executable contains the login-server IP as ASCII strings. Upstream documents
  THREE occurrences of "127.0.0.1" stored right above each other. Verified on the official
  HeavenMS-localhost-WINDOW.exe (Cosmic-client commit 6b7328b): three occurrences at uniform
  16-byte spacing, each in a fixed-size null-padded field ("127.0.0.1" + 7 NUL bytes).

Safety model (never corrupt, refuse instead):
  * Never modifies the input file; always writes a separate output copy.
    Refuses when input and output resolve to the same file.
  * Validates the target as dotted-quad IPv4 (IPv4 only; the v83 client has no IPv6 support).
  * Scans the binary, requires exactly the expected number of source-IP occurrences
    (default 3, override with --expected-count).
  * Derives the per-occurrence field length from the spacing between occurrences, requires
    uniform spacing, requires the padding area to be all NUL, and requires the new IP plus
    NUL terminator to fit the field. Any deviation aborts without writing anything.
  * After patching (in memory) re-verifies: identical file size, zero remaining source-IP
    occurrences, exactly N target-IP occurrences; only then writes the output file and
    reports SHA-256 of input and output.

Usage:
  patch-client-ip.py INPUT_EXE TARGET_IP OUTPUT_EXE [--source-ip 127.0.0.1]
                     [--expected-count 3] [--dry-run]
  patch-client-ip.py --self-test
"""

import argparse
import ipaddress
import os
import sys

DEFAULT_SOURCE_IP = "127.0.0.1"
DEFAULT_EXPECTED_COUNT = 3


class PatchError(Exception):
    pass


def validate_ipv4(ip: str) -> str:
    try:
        addr = ipaddress.IPv4Address(ip)
    except ValueError:
        raise PatchError(f"'{ip}' is not a valid dotted-quad IPv4 address")
    return str(addr)


def find_occurrences(data: bytes, needle: bytes) -> list:
    offsets = []
    idx = 0
    while True:
        i = data.find(needle, idx)
        if i < 0:
            return offsets
        offsets.append(i)
        idx = i + 1


def analyze_fields(data: bytes, offsets: list, needle_len: int) -> int:
    """Derive and validate the fixed field length from occurrence spacing."""
    if len(offsets) < 2:
        raise PatchError("cannot derive field length from fewer than 2 occurrences; refusing")
    spacings = {offsets[i + 1] - offsets[i] for i in range(len(offsets) - 1)}
    if len(spacings) != 1:
        raise PatchError(f"non-uniform spacing between IP occurrences: {sorted(spacings)}; refusing")
    field_len = spacings.pop()
    if field_len <= needle_len:
        raise PatchError(f"field length {field_len} not larger than IP string; layout unexpected")
    for off in offsets:
        padding = data[off + needle_len: off + field_len]
        if any(b != 0 for b in padding):
            raise PatchError(
                f"padding bytes after IP at offset 0x{off:x} are not all NUL: "
                f"{padding.hex(' ')}; refusing"
            )
    return field_len


def patch_bytes(data: bytes, source_ip: str, target_ip: str, expected_count: int):
    src = source_ip.encode("ascii")
    dst = target_ip.encode("ascii")
    offsets = find_occurrences(data, src)
    if len(offsets) != expected_count:
        raise PatchError(
            f"found {len(offsets)} occurrences of {source_ip}, expected {expected_count}; "
            f"executable layout unexpected, refusing"
        )
    field_len = analyze_fields(data, offsets, len(src))
    if len(dst) + 1 > field_len:
        raise PatchError(
            f"target IP '{target_ip}' ({len(dst)} chars + NUL) does not fit the "
            f"{field_len}-byte field; refusing instead of corrupting"
        )
    out = bytearray(data)
    for off in offsets:
        field = dst + b"\x00" * (field_len - len(dst))
        out[off: off + field_len] = field
    out = bytes(out)
    if len(out) != len(data):
        raise PatchError("internal error: output size changed; refusing")
    if find_occurrences(out, src):
        raise PatchError("source IP still present after patching; refusing")
    if len(find_occurrences(out, dst)) != expected_count:
        raise PatchError("target IP occurrence count mismatch after patching; refusing")
    return out, offsets, field_len


def sha256_of(path: str) -> str:
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def self_test() -> int:
    """Exercise the patch logic on synthetic buffers (no real executable needed)."""
    ip = b"127.0.0.1"
    field = ip + b"\x00" * 7          # 16-byte null-padded field, as in the real exe
    junk_before = b"\x55" * 32
    junk_after = b"\x77" * 32
    blob = junk_before + field + field + field + junk_after

    patched, offsets, field_len = patch_bytes(blob, "127.0.0.1", "192.168.2.104", 3)
    assert field_len == 16, field_len
    assert offsets == [32, 48, 64], offsets
    assert patched.count(b"192.168.2.104") == 3
    assert patched.count(ip) == 0
    assert len(patched) == len(blob)

    # max-length IPv4 must fit a 16-byte field
    patched2, _, _ = patch_bytes(blob, "127.0.0.1", "255.255.255.255", 3)
    assert patched2.count(b"255.255.255.255") == 3

    # too-small field must refuse (15-char IP + NUL = 16 > 12)
    tight = (ip + b"\x00" * 3) * 3
    try:
        patch_bytes(tight, "127.0.0.1", "255.255.255.255", 3)
        raise AssertionError("expected refusal for too-small field")
    except PatchError:
        pass

    # non-NUL padding must refuse
    bad_pad = junk_before + (ip + b"\x00" * 6 + b"\xAA") * 3 + junk_after
    try:
        patch_bytes(bad_pad, "127.0.0.1", "10.0.0.1", 3)
        raise AssertionError("expected refusal for non-NUL padding")
    except PatchError:
        pass

    # wrong occurrence count must refuse
    try:
        patch_bytes(blob, "127.0.0.1", "10.0.0.1", 4)
        raise AssertionError("expected refusal for wrong count")
    except PatchError:
        pass

    # invalid IPv4 must refuse
    try:
        validate_ipv4("999.1.1.1")
        raise AssertionError("expected refusal for invalid IPv4")
    except PatchError:
        pass

    print("self-test OK: patch logic, field detection, fit checks and refusals work")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Safe server-IP patcher for the Cosmic v83 client exe")
    ap.add_argument("input", nargs="?")
    ap.add_argument("target_ip", nargs="?")
    ap.add_argument("output", nargs="?")
    ap.add_argument("--source-ip", default=DEFAULT_SOURCE_IP)
    ap.add_argument("--expected-count", type=int, default=DEFAULT_EXPECTED_COUNT)
    ap.add_argument("--dry-run", action="store_true", help="analyze and report without writing")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        if len(sys.argv) != 2:
            ap.error("--self-test cannot be combined with other arguments")
        return self_test()
    if not (args.input and args.target_ip and args.output):
        ap.error("INPUT_EXE, TARGET_IP and OUTPUT_EXE are required (or use --self-test)")

    try:
        target_ip = validate_ipv4(args.target_ip)
        validate_ipv4(args.source_ip)
        in_path = os.path.realpath(args.input)
        out_path = os.path.realpath(args.output)
        if in_path == out_path:
            raise PatchError("output path equals input path; refusing to modify the only copy")
        if not os.path.isfile(in_path):
            raise PatchError(f"input not found: {in_path}")
        data = open(in_path, "rb").read()
        if data[:2] != b"MZ":
            raise PatchError("input does not look like a Windows executable (no MZ header)")
        print(f"input: {in_path}")
        print(f"  size: {len(data)} bytes  sha256: {sha256_of(in_path)}")
        if target_ip == args.source_ip:
            offsets = find_occurrences(data, args.source_ip.encode("ascii"))
            if len(offsets) != args.expected_count:
                raise PatchError(
                    f"found {len(offsets)} occurrences of {args.source_ip}, expected "
                    f"{args.expected_count}; executable layout unexpected"
                )
            field_len = analyze_fields(data, offsets, len(args.source_ip.encode("ascii")))
            print(f"layout: {len(offsets)} occurrence(s) of {args.source_ip} at "
                  + ", ".join(f"0x{o:x}" for o in offsets) + f"; field length {field_len} bytes")
            print("target IP equals source IP: nothing to patch")
            return 0
        patched, offsets, field_len = patch_bytes(data, args.source_ip, target_ip, args.expected_count)
        print(f"layout: {len(offsets)} occurrence(s) of {args.source_ip} at "
              + ", ".join(f"0x{o:x}" for o in offsets) + f"; field length {field_len} bytes")
        if args.dry_run:
            print("dry-run: no file written")
            return 0
        try:
            with open(out_path, "xb") as f:
                f.write(patched)
        except FileExistsError:
            raise PatchError(f"output already exists: {out_path}; refusing to overwrite")
        except OSError as e:
            raise PatchError(f"could not write output: {e}")
        # verification pass on the written file
        reread = open(out_path, "rb").read()
        if reread != patched:
            os.remove(out_path)
            raise PatchError("written file does not match patched content")
        print(f"modified {len(offsets)} location(s); wrote {out_path}")
        print(f"  size: {len(reread)} bytes  sha256: {sha256_of(out_path)}")
        return 0
    except PatchError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
