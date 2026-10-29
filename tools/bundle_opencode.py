"""Bundle a locally built official dev executable without replacing the upstream WebUI."""
import argparse
import gzip
import hashlib
import json
import pathlib
import shutil
import struct
from check_elf import check

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("abi", choices=["arm64-v8a", "x86_64"])
parser.add_argument("binary", type=pathlib.Path)
args = parser.parse_args()
binary = args.binary
header = binary.open("rb").read(64)
assert header[:6] == b"\x7fELF\x02\x01"
assert struct.unpack_from("<H", header, 18)[0] == (183 if args.abi == "arm64-v8a" else 62)
if args.abi == "arm64-v8a":
    check(binary)
lock = json.loads((ROOT / "runtime/opencode-dev.lock.json").read_text())
output = ROOT / "app/runtime-assets" / args.abi / "runtime"
pack = json.loads((output / "pack.json").read_text())
with binary.open("rb") as source, (output / "opencode.pack").open("wb") as raw:
    with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=6) as archive:
        shutil.copyfileobj(source, archive, 1024 * 1024)
pack["opencode"] = {
    "archive": "opencode.pack", "version": lock["buildVersion"], "commit": lock["commit"],
    "size": binary.stat().st_size, "sha256": hashlib.file_digest(binary.open("rb"), "sha256").hexdigest(),
    "archiveSize": (output / "opencode.pack").stat().st_size,
    "archiveSha256": hashlib.file_digest((output / "opencode.pack").open("rb"), "sha256").hexdigest(),
}
pack["minimumFreeBytes"] = binary.stat().st_size + pack.get("unpackedBytes", 0) + 512 * 1024 * 1024
(output / "pack.json").write_text(json.dumps(pack, indent=2) + "\n", encoding="utf-8")
print(json.dumps(pack["opencode"], indent=2))
