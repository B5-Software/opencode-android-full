"""Materialize a digest-checked, snapshot-pinned apt mirror for an offline ARM64 builder.

The builder's apt still verifies Debian InRelease signatures. This helper only
downloads data; it does not execute package contents or change host networking.
"""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import shlex
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROFILE = json.loads((ROOT / "runtime/developer-profile.json").read_text())
MIRROR = ROOT / ".cache/developer-mirror"
SOURCES = [("debian", "trixie"), ("debian", "trixie-updates"), ("debian-security", "trixie-security")]


def acquire(url, destination, expected=None, size=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        digest = hashlib.file_digest(destination.open("rb"), "sha256").hexdigest()
        if expected is None or digest == expected:
            return digest
        raise ValueError(f"Cached checksum mismatch: {destination}")
    partial = destination.with_suffix(destination.suffix + ".partial")
    for attempt in range(4):
        try:
            digest = hashlib.sha256()
            count = 0
            with urllib.request.urlopen(url, timeout=120) as source, partial.open("wb") as output:
                while data := source.read(1024 * 1024):
                    digest.update(data); count += len(data); output.write(data)
            result = digest.hexdigest()
            if expected is not None and expected != result:
                raise ValueError(f"SHA-256 mismatch: {url}")
            if size is not None and count != size:
                raise ValueError(f"Size mismatch: {url}")
            partial.replace(destination)
            return result
        except ValueError:
            raise
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def indexes():
    lock_path = ROOT / "runtime/developer-source-lock.json"
    previous = json.loads(lock_path.read_text()) if lock_path.exists() else {}
    records = {}
    for archive, suite in SOURCES:
        base = f"https://snapshot.debian.org/archive/{archive}/{PROFILE['snapshot']}"
        directory = MIRROR / archive / "dists" / suite
        inrelease = directory / "InRelease"
        key = f"{archive}/{suite}"
        checksum = acquire(f"{base}/dists/{suite}/InRelease", inrelease, previous.get(key, {}).get("inReleaseSha256"))
        text = inrelease.read_text(encoding="utf-8")
        index_name = "main/binary-arm64/Packages.xz"
        match = re.search(r"^\s+([a-f0-9]{64})\s+(\d+)\s+" + re.escape(index_name) + r"\s*$", text, re.MULTILINE)
        assert match, f"Missing SHA256 for {index_name}"
        expected, size = match.group(1), int(match.group(2))
        acquire(f"{base}/dists/{suite}/{index_name}", directory / index_name, expected, size)
        records[key] = {"snapshot": PROFILE["snapshot"], "url": base, "inReleaseSha256": checksum, "packagesSha256": expected, "size": size}
        print(f"Mirror metadata ready: {key}", flush=True)
    if previous:
        assert records == previous, "Pinned apt metadata changed"
    lock_path.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")


def packages(locked=False):
    if locked:
        records = json.loads((ROOT / "runtime/developer-deb-lock.json").read_text())
        assert records and isinstance(records, list)
        for record in records:
            relative = pathlib.PurePosixPath(record["file"])
            assert not relative.is_absolute() and ".." not in relative.parts and "\\" not in str(relative)
            assert relative.parts[0] in ("debian", "debian-security")
            expected_url = f"https://snapshot.debian.org/archive/{relative.parts[0]}/{PROFILE['snapshot']}/" + "/".join(relative.parts[1:])
            assert record["url"] == expected_url and re.fullmatch(r"[a-f0-9]{64}", record["sha256"])
            assert isinstance(record["size"], int) and record["size"] > 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda item: acquire(item["url"], MIRROR / item["file"], item["sha256"], item["size"]), records))
        print(f"Verified {len(records)} locked developer package archives", flush=True)
        return
    queue = ROOT / ".cache/developer-arm64-uris.txt"
    requirements = []
    for line in queue.read_text().splitlines():
        if not line.startswith("'"):
            continue
        uri, filename, size, digest = shlex.split(line)
        assert digest.startswith("SHA256:"), f"apt must print SHA256: {line}"
        path = urllib.parse.unquote(urllib.parse.urlsplit(uri).path)
        prefix = "/opt/devkit-mirror/"
        assert path.startswith(prefix)
        relative = pathlib.PurePosixPath(path.removeprefix(prefix))
        assert ".." not in relative.parts and relative.parts[0] in ("debian", "debian-security")
        archive = relative.parts[0]
        destination = MIRROR.joinpath(*relative.parts)
        url = f"https://snapshot.debian.org/archive/{archive}/{PROFILE['snapshot']}/" + "/".join(relative.parts[1:])
        requirements.append({"file": str(relative), "url": url, "size": int(size), "sha256": digest.removeprefix("SHA256:")})
    assert requirements, "No apt packages were resolved; inspect the builder log"
    counter = 0
    def fetch(record):
        acquire(record["url"], MIRROR / record["file"], record["sha256"], record["size"])
        return record
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for _ in pool.map(fetch, requirements):
            counter += 1
            if counter % 10 == 0 or counter == len(requirements):
                print(f"Verified developer packages: {counter}/{len(requirements)}", flush=True)
    (ROOT / "runtime/developer-deb-lock.json").write_text(json.dumps(requirements, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=["indexes", "packages"])
    parser.add_argument("--locked", action="store_true", help="download the committed package graph without re-resolving or rewriting its lock")
    args = parser.parse_args()
    if args.phase == "indexes": indexes()
    else: packages(args.locked)
