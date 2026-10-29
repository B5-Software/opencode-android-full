#!/usr/bin/env python3
"""Prepare a matched OpenCode update manifest; no download, signing or installation."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--min-host-code", type=int, default=1)
    args = parser.parse_args()
    directory = ROOT / "app/runtime-assets/arm64-v8a/runtime"
    record = json.loads((directory / "pack.json").read_text())["opencode"]
    lock = json.loads((ROOT / "runtime/opencode-dev.lock.json").read_text())
    assert record["archive"] == "opencode.pack" and record["commit"] == lock["commit"] and record["version"] == lock["buildVersion"]
    count = 0
    checksum = hashlib.sha256()
    with gzip.open(directory / "opencode.pack", "rb") as source:
        while block := source.read(1024 * 1024):
            count += len(block)
            assert count <= record["size"] <= 1024**3
            checksum.update(block)
    assert count == record["size"] and checksum.hexdigest() == record["sha256"]
    archive = directory / "opencode.pack"
    with archive.open("rb") as source:
        compressed_sha = hashlib.file_digest(source, "sha256").hexdigest()
    manifest = {"format": 1, "kind": "opencode-pair", "abi": "arm64-v8a", "minHostVersionCode": args.min_host_code,
                "version": record["version"], "commit": record["commit"], "repository": lock["repository"],
                "binary": {"size": count, "sha256": checksum.hexdigest()},
                "archive": {"size": archive.stat().st_size, "sha256": compressed_sha}}
    assert 0 < args.min_host_code <= 2_100_000_000 and archive.stat().st_size <= 512 * 1024**2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes((json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n").encode("ascii"))
    print(f"Update manifest prepared: {record['version']}")


if __name__ == "__main__":
    main()
