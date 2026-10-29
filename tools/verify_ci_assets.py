#!/usr/bin/env python3
"""Validate cached ARM64 inputs before Gradle consumes them."""
import gzip
import hashlib
import json
from pathlib import Path
import struct
from check_elf import check

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    directory = ROOT / "app/runtime-assets/arm64-v8a/runtime"
    pack = json.loads((directory / "pack.json").read_text())
    base = json.loads((ROOT / "runtime/debian-arm64-v8a.json").read_text())
    profile = json.loads((ROOT / "runtime/developer-profile.json").read_text())
    upstream = json.loads((ROOT / "runtime/opencode-dev.lock.json").read_text())
    assert pack["format"] == 1 and pack["abi"] == "arm64-v8a" and pack["suite"] == "trixie"
    assert pack["archive"] == "debian.pack" and digest(directory / "debian.pack") == pack["sha256"]
    assert pack["baseImageSha256"] == base["sha256"]
    developer = pack["developer"]
    assert developer["profile"] == profile["id"] and developer["snapshot"] == profile["snapshot"]
    assert developer["packages"] == profile["packages"]
    assert developer["packageArchiveLockSha256"] == digest(ROOT / "runtime/developer-deb-lock.json")
    assert developer["packageInventorySha256"] == digest(ROOT / "runtime/developer-packages-arm64.tsv")
    record = pack["opencode"]
    assert record["archive"] == "opencode.pack" and record["commit"] == upstream["commit"] and record["version"] == upstream["buildVersion"]
    count = 0
    checksum = hashlib.sha256()
    with gzip.open(directory / "opencode.pack", "rb") as binary:
        while block := binary.read(1024 * 1024):
            count += len(block)
            assert count <= record["size"]
            checksum.update(block)
    assert count == record["size"] and checksum.hexdigest() == record["sha256"]
    for name in ("libproot.so", "libproot-loader.so"):
        path = ROOT / "app/src/main/jniLibs/arm64-v8a" / name
        assert struct.unpack_from("<H", path.read_bytes(), 18)[0] == 183
        check(path)
    terminal = json.loads((ROOT / "runtime/terminal-files.json").read_text())
    for name, expected in terminal.items():
        assert Path(name).name == name
        assert digest(ROOT / "app/src/main/assets/terminal/vendor" / name) == expected
    print("PASS locked ARM64 developer pack, official pair, native ELF and terminal assets")


if __name__ == "__main__":
    main()
