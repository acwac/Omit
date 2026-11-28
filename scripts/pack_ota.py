#!/usr/bin/env python3
"""Packs the built web UI into a deterministic OTA archive.

The Android updater downloads `ota-ui.zip`, verifies its SHA-256 against
`version.json`, and only then swaps the UI bundle in. Two rules follow from that:

1. The archive must never accumulate old bundles. `zip -r` merges into an
   existing file, which previously shipped every historical hashed asset.
2. The bytes must be reproducible. Timestamps and file order are pinned so the
   hash published in `version.json` is exactly the hash clients download.

Usage:
    python3 scripts/pack_ota.py <ui_dir> <out_zip>
    python3 scripts/pack_ota.py --write-version <ui_dir> <out_zip> <version.json>
"""

from __future__ import annotations

import hashlib
import json
import os
import sys
import zipfile

FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def collect(ui_dir: str) -> list[tuple[str, bytes]]:
    """Every shipped UI file, as (arcname, data), in a stable order."""
    files: list[tuple[str, bytes]] = []
    for root, dirs, names in os.walk(ui_dir):
        dirs.sort()
        for name in sorted(names):
            if name.startswith(".") or name.endswith(".map"):
                continue
            full = os.path.join(root, name)
            arc = os.path.relpath(full, ui_dir).replace(os.sep, "/")
            with open(full, "rb") as handle:
                files.append((arc, handle.read()))
    files.sort(key=lambda item: item[0])
    return files


def pack(ui_dir: str, out_zip: str) -> str:
    files = collect(ui_dir)
    if not files:
        raise SystemExit(f"nothing to pack: {ui_dir} is empty")

    parent = os.path.dirname(os.path.abspath(out_zip))
    os.makedirs(parent, exist_ok=True)
    tmp = out_zip + ".tmp"
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for arc, data in files:
            info = zipfile.ZipInfo(arc, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)
    os.replace(tmp, out_zip)

    digest = hashlib.sha256(open(out_zip, "rb").read()).hexdigest()
    print(f"Packed {len(files)} files into {out_zip} ({os.path.getsize(out_zip):,} bytes)")
    print(f"SHA-256 {digest}")
    return digest


def write_version(version_json: str, digest: str) -> None:
    with open(version_json, "r", encoding="utf-8") as handle:
        doc = json.load(handle)
    doc["otaSha256"] = digest
    with open(version_json, "w", encoding="utf-8") as handle:
        json.dump(doc, handle, indent=2)
        handle.write("\n")
    print(f"Updated {version_json} with otaSha256")


def main(argv: list[str]) -> int:
    write = "--write-version" in argv
    args = [a for a in argv if a != "--write-version"]
    if write:
        if len(args) != 3:
            print(__doc__)
            return 2
        ui_dir, out_zip, version_json = args
    else:
        if len(args) != 2:
            print(__doc__)
            return 2
        ui_dir, out_zip = args
        version_json = ""

    digest = pack(ui_dir, out_zip)
    if write:
        write_version(version_json, digest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
