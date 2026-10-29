#!/usr/bin/env python3
"""Pack a pre-signed manifest and exact gzip payload; no key material enters the ZIP."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--payload", type=Path, default=ROOT / "app/runtime-assets/arm64-v8a/runtime/opencode.pack")
    args = parser.parse_args()
    manifest = args.manifest.read_bytes()
    signature = args.signature.read_bytes()
    assert 0 < len(manifest) <= 16 * 1024 and 0 < len(signature) <= 8 * 1024
    record = json.loads(manifest)
    payload = args.payload
    with payload.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    assert record["archive"]["sha256"] == digest and record["archive"]["size"] == payload.stat().st_size
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in (("manifest.json", manifest), ("manifest.sig", signature)):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            archive.writestr(info, data)
        info = zipfile.ZipInfo("opencode.pack", (1980, 1, 1, 0, 0, 0))
        info.file_size = payload.stat().st_size
        with payload.open("rb") as source, archive.open(info, "w") as output:
            while block := source.read(1024 * 1024): output.write(block)
    print("Signed matched-runtime package created")


if __name__ == "__main__":
    main()
