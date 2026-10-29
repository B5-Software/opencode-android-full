"""Check packaged ABI, native alignment, pinned assets and Android API contract."""
import argparse
import gzip
import hashlib
import io
import json
import os
import pathlib
import re
import struct
import subprocess
import zipfile
from check_elf import check_data

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=pathlib.Path)
parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], default="arm64-v8a")
parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
parser.add_argument("--require-core-proof", action="store_true")
parser.add_argument("--require-license-materials", action="store_true")
args = parser.parse_args()
with zipfile.ZipFile(args.apk) as archive:
    native = [n for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so")]
    assert native and all(n.startswith(f"lib/{args.abi}/") for n in native), native
    for required in ["libproot.so", "libproot-loader.so", "libruntime-host.so"]:
        assert f"lib/{args.abi}/{required}" in native, f"APK missing {required}"
    for name in native:
        data = archive.read(name)
        assert struct.unpack_from("<H", data, 18)[0] == (183 if args.abi == "arm64-v8a" else 62)
        check_data(data, name)
        entry = archive.getinfo(name)
        if entry.compress_type == zipfile.ZIP_STORED:
            with args.apk.open("rb") as raw:
                raw.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
            assert (entry.header_offset + 30 + name_size + extra_size) % 16384 == 0
    pack = json.loads(archive.read("assets/runtime/pack.json"))
    assert pack["abi"] == args.abi and pack["suite"] == "trixie"
    assert hashlib.sha256(archive.read("assets/runtime/" + pack["archive"])).hexdigest() == pack["sha256"]
    if "opencode" in pack:
        record = pack["opencode"]
        digest = hashlib.sha256()
        count = 0
        with archive.open("assets/runtime/" + record["archive"]) as compressed, gzip.GzipFile(fileobj=compressed) as binary:
            while data := binary.read(1024 * 1024):
                digest.update(data); count += len(data)
        assert count == record["size"] and digest.hexdigest() == record["sha256"]
        proof_names = ("assets/runtime/opencode-publisher.json", "assets/runtime/opencode-publisher.sig")
        if args.require_core_proof or any(name in archive.namelist() for name in proof_names):
            assert all(name in archive.namelist() for name in proof_names)
            assert 0 < archive.getinfo(proof_names[0]).file_size <= 16384 and 0 < archive.getinfo(proof_names[1]).file_size <= 8192
            proof = json.loads(archive.read(proof_names[0]))
            assert proof["format"] == 1 and proof["kind"] == "opencode-pair" and proof["abi"] == args.abi
            assert proof["repository"] == "https://github.com/anomalyco/opencode.git"
            assert proof["binary"] == {"size": count, "sha256": record["sha256"]}
            assert proof["version"] == record["version"] and proof["commit"] == record["commit"]
            with archive.open("assets/runtime/" + record["archive"]) as compressed:
                packed_sha = hashlib.file_digest(compressed, "sha256").hexdigest()
            assert proof["archive"] == {"size": archive.getinfo("assets/runtime/" + record["archive"]).file_size, "sha256": packed_sha}
            print("PASS bundled publisher metadata matches the complete official pair (signature checked separately)")
        assert record["commit"] == json.loads((pathlib.Path(__file__).resolve().parents[1] / "runtime/opencode-dev.lock.json").read_text())["commit"]
        print(f"PASS matching official OpenCode dev pack: {record['version']}")
    else:
        raise AssertionError("APK has no official OpenCode dev executable")
    if args.require_license_materials:
        from notice_contract import check_notices
        notice_lock=json.loads((pathlib.Path(__file__).resolve().parents[1]/'third_party/maven-sources.lock.json').read_text(encoding='utf-8'))
        check_notices(archive,pack['opencode']['commit'],notice_lock)
        toolchain_lock=json.loads((pathlib.Path(__file__).resolve().parents[1]/'third_party/toolchain-notices.lock.json').read_text(encoding='utf-8'))
        for notice in toolchain_lock['notices']:
            data=archive.read(notice['asset'])
            assert len(data)==notice['size'] and hashlib.sha256(data).hexdigest()==notice['sha256'], 'Packaged toolchain notice differs from its source pin'
        print('PASS embedded Maven/npm notice archives and official source identity')
assert args.sdk, "Set ANDROID_HOME or --sdk to validate the manifest"
tools = pathlib.Path(args.sdk) / "build-tools/36.0.0"
suffix = ".exe" if os.name == "nt" else ""
badging = subprocess.check_output([str(tools / f"aapt2{suffix}"), "dump", "badging", str(args.apk)], text=True, encoding="utf-8")
assert ("sdkVersion:'29'" in badging or "minSdkVersion:'29'" in badging) and "targetSdkVersion:'36'" in badging
tree = subprocess.check_output([str(tools / f"aapt2{suffix}"), "dump", "xmltree", str(args.apk), "--file", "AndroidManifest.xml"], text=True, encoding="utf-8")
for line in tree.splitlines():
    if "android:process(" not in line:
        continue
    value = re.search(r'="([^"]+)"', line)
    assert value, f"Process names must be literal strings: {line}"
    name = value.group(1).removeprefix(":")
    assert re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*", name), f"Invalid Android process identifier: {value.group(1)}"
print("PASS manifest process identifiers")
extract = next(line for line in tree.splitlines() if "extractNativeLibs" in line)
assert "true" in extract or "0xffffffff" in extract.lower(), extract
subprocess.run([str(tools / f"zipalign{suffix}"), "-c", "-P", "16", "4", str(args.apk)], check=True)
print(f"PASS APK: {args.abi}, minSdk=29, targetSdk=36, extracted native executables, 16KB packaging/alignment")
