#!/usr/bin/env python3
"""Package the pinned native component sources, retaining licenses and local patches."""
import io
import gzip
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
commit = "4ac94ecbb567608725e1d68d5ea617800a76c9c4"
source = ROOT / ".cache/native/proot-source"
destination = ROOT / ".cache/ci-native-sources.tar.gz"
data = subprocess.check_output(["git", "-C", str(source), "archive", "--format=tar", commit])
def canonical(entry):
    entry.uid = entry.gid = 0
    entry.uname = entry.gname = ""
    entry.mtime = 0
    entry.pax_headers = {}
    entry.mode = 0o755 if entry.isdir() or entry.mode & 0o111 else 0o644
    return entry

with destination.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) as compressed, tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as output:
    with tarfile.open(fileobj=io.BytesIO(data)) as original:
        for entry in original:
            stream = original.extractfile(entry) if entry.isfile() else None
            entry.name = "proot/" + entry.name
            output.addfile(canonical(entry), stream)
    for directory in ("talloc-2.5.0", "sqlite-autoconf-3540100"):
        output.add(ROOT / ".cache/native" / directory, arcname=directory, filter=canonical)
    output.add(ROOT / "native-host", arcname="android-build", filter=canonical)
print("Native corresponding-source archive prepared")
