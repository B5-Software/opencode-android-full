#!/usr/bin/env python3
"""Append a publisher proof to an unsigned APK; the caller must then align/sign it."""
import argparse
import json
from pathlib import Path
import shutil
import zipfile


def inject(source: Path, manifest: Path, signature: Path, output: Path):
    assert source.resolve() != output.resolve() and not output.exists()
    raw, signed = manifest.read_bytes(), signature.read_bytes()
    assert 0 < len(raw) <= 16384 and 0 < len(signed) <= 8192
    metadata = json.loads(raw)
    names = ("assets/runtime/opencode-publisher.json", "assets/runtime/opencode-publisher.sig")
    with zipfile.ZipFile(source) as archive:
        assert not any(name in archive.namelist() for name in names)
        assert not any(name.startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".EC", ".DSA")) for name in archive.namelist())
        bundled = json.loads(archive.read("assets/runtime/pack.json"))["opencode"]
        assert metadata["binary"] == {"size": bundled["size"], "sha256": bundled["sha256"]}
        assert metadata["version"] == bundled["version"] and metadata["commit"] == bundled["commit"]
        central = archive.start_dir
    with source.open("rb") as stream:
        stream.seek(max(0, central - 16))
        assert stream.read(16) != b"APK Sig Block 42", "Use the unsigned release input"
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, output)
    with zipfile.ZipFile(output, "a") as archive:
        for name, payload in zip(names, (raw, signed)):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            archive.writestr(info, payload)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unsigned-apk", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    inject(args.unsigned_apk, args.manifest, args.signature, args.output)
    print("Publisher proof embedded; APK still requires zipalign and signing")
