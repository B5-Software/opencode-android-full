#!/usr/bin/env python3
"""Prepare or frame an APK-publisher-signed HTTPS update feed. Never publish or execute it."""
import argparse
import hashlib
import json
import re
import struct
from pathlib import Path
from urllib.parse import quote, urlsplit
import zipfile

MAX_PACKAGE = 512 * 1024**2 + 65536


def prepare(package, repository, tag):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or any(part in (".", "..") for part in repository.split("/")):
        raise ValueError("Invalid GitHub repository")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", tag):
        raise ValueError("Invalid release tag")
    size = package.stat().st_size
    if not 1 <= size <= MAX_PACKAGE:
        raise ValueError("Package size")
    with zipfile.ZipFile(package) as archive:
        entries = archive.infolist()
        if [item.filename for item in entries] != ["manifest.json", "manifest.sig", "opencode.pack"]:
            raise ValueError("Package entries")
        if not 1 <= entries[0].file_size <= 16384 or not 1 <= entries[1].file_size <= 8192:
            raise ValueError("Proof bounds")
        if any(item.compress_type != zipfile.ZIP_STORED for item in entries):
            raise ValueError("Package encoding")
        body = archive.read(entries[0]); manifest = json.loads(body)
        if manifest["format"] != 1 or manifest["kind"] != "opencode-pair" or manifest["abi"] != "arm64-v8a":
            raise ValueError("Package format")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,95}", manifest["version"]) or not re.fullmatch(r"[a-f0-9]{40}", manifest["commit"]):
            raise ValueError("Core identity")
        if type(manifest["minHostVersionCode"]) is not int or not 1 <= manifest["minHostVersionCode"] <= 2100000000:
            raise ValueError("Host version")
    with package.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    name = quote(package.name, safe="")
    url = f"https://github.com/{repository}/releases/download/{quote(tag, safe='')}/{name}"
    if urlsplit(url).scheme != "https":
        raise ValueError("HTTPS required")
    feed = {"format": 1, "kind": "opencode-pair-feed", "abi": "arm64-v8a",
            "version": manifest["version"], "commit": manifest["commit"],
            "minHostVersionCode": manifest["minHostVersionCode"], "packageUrl": url,
            "packageSize": size, "packageSha256": digest, "manifestSha256": hashlib.sha256(body).hexdigest(),
            "binarySha256": manifest["binary"]["sha256"]}
    return (json.dumps(feed, sort_keys=True, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii")


def frame(body, signature):
    if not 1 <= len(body) <= 16384 or not 1 <= len(signature) <= 8192:
        raise ValueError("Feed bounds")
    return b"OCAFEED1" + struct.pack(">II", len(body), len(signature)) + body + signature


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_command = commands.add_parser("prepare")
    prepare_command.add_argument("--package", type=Path, required=True)
    prepare_command.add_argument("--repository", required=True)
    prepare_command.add_argument("--tag", required=True)
    prepare_command.add_argument("--output", type=Path, required=True)
    pack = commands.add_parser("frame")
    pack.add_argument("--body", type=Path, required=True)
    pack.add_argument("--signature", type=Path, required=True)
    pack.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = prepare(args.package, args.repository, args.tag) if args.command == "prepare" else frame(args.body.read_bytes(), args.signature.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True); args.output.write_bytes(result)
    print("Update feed prepared; publication is a separate action.")


if __name__ == "__main__":
    main()
