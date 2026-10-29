#!/usr/bin/env python3
"""Inventory the Debian portion of an actual APK; retain verbatim license texts, not legal conclusions."""
import argparse
from collections import deque
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
from urllib.parse import quote
import zipfile

STATUS = "var/lib/dpkg/status"
INVENTORY = "usr/share/opencode-android/developer-packages.tsv"
DOCS = "usr/share/doc/"
COMMON = "usr/share/common-licenses/"
MAX_FILE = 32 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
MAX_ENTRY = 512 * 1024 * 1024
MAX_UNPACKED = 4 * 1024**3


def sha(data):
    return hashlib.sha256(data).hexdigest()


def stanzas(text):
    fields = {}
    key = None
    for line in text.splitlines() + [""]:
        if not line:
            if fields:
                yield fields
            fields, key = {}, None
        elif line[0] in " \t":
            if key is None:
                raise ValueError("Continuation without field")
            fields[key] += "\n" + line[1:]
        else:
            key, separator, value = line.partition(":")
            if not separator or key in fields:
                raise ValueError("Malformed or duplicate package field")
            fields[key] = value.lstrip()


def member_name(value):
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or "\\" in value or "\x00" in value:
        raise ValueError("Unsafe archive member")
    return str(path) if str(path) != "." else ""


class HashReader:
    def __init__(self, stream):
        self.stream, self.digest = stream, hashlib.sha256()

    def read(self, size=-1):
        data = self.stream.read(size)
        self.digest.update(data)
        return data


def collect(stream):
    """Read metadata only. Never extract archive paths or follow host filesystem links."""
    entries = {}
    total = 0
    unpacked = 0
    count = 0
    with tarfile.open(fileobj=stream, mode="r|*") as archive:
        for entry in archive:
            count += 1
            unpacked += 512 + ((entry.size + 511) // 512) * 512
            if count > 250000 or not 0 <= entry.size <= MAX_ENTRY or unpacked > MAX_UNPACKED:
                raise ValueError("Runtime archive exceeds inventory limits")
            name = member_name(entry.name)
            if not (name in (STATUS, INVENTORY) or name.startswith((DOCS, COMMON))):
                continue
            if entry.issym() or entry.islnk():
                entries[name] = ("hard" if entry.islnk() else "symbolic", entry.linkname)
            elif entry.isfile() and (name in (STATUS, INVENTORY) or name.startswith(COMMON) or name.endswith("/copyright")):
                if not 0 <= entry.size <= MAX_FILE or total + entry.size > MAX_TOTAL:
                    raise ValueError("Oversized license metadata")
                data = archive.extractfile(entry).read(MAX_FILE + 1)
                if len(data) != entry.size:
                    raise ValueError("Truncated license metadata")
                total += len(data)
                entries[name] = ("file", data)
            elif entry.isdir():
                entries[name] = ("directory", None)
            else:
                entries[name] = ("other", None)
    return entries


def resolve(entries, name):
    pending = deque(PurePosixPath(name).parts)
    parts = []
    links = 0
    while pending:
        part = pending.popleft()
        if part in ("", ".", "/"):
            continue
        if part == "..":
            if not parts:
                raise ValueError("License link escapes archive root")
            parts.pop()
            continue
        parts.append(part)
        entry = entries.get("/".join(parts))
        if entry and entry[0] in ("symbolic", "hard"):
            links += 1
            if links > 40:
                raise ValueError("Cyclic or excessive license links")
            target = entry[1]
            if "\\" in target or "\x00" in target:
                raise ValueError("Unsafe license link")
            if entry[0] == "hard" or target.startswith("/"):
                parts = []
            else:
                parts.pop()
            pending.extendleft(reversed(PurePosixPath(target).parts))
    result = "/".join(parts)
    if not result.startswith((DOCS, COMMON)) or entries.get(result, (None,))[0] != "file":
        raise ValueError("License target is missing, outside documentation, or not regular")
    return result, entries[result][1]


def installed(entries, expected_inventory_sha):
    if entries.get(STATUS, (None,))[0] != "file" or entries.get(INVENTORY, (None,))[0] != "file":
        raise ValueError("Package status/inventory missing")
    inventory = entries[INVENTORY][1]
    if sha(inventory) != expected_inventory_sha:
        raise ValueError("Embedded inventory hash mismatch")
    packages = {}
    for item in stanzas(entries[STATUS][1].decode("utf-8")):
        if item.get("Status") != "install ok installed":
            continue
        name, architecture, version = item["Package"], item["Architecture"], item["Version"]
        if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name) or not re.fullmatch(r"[a-z0-9-]+", architecture) or not re.fullmatch(r"[A-Za-z0-9.+:~_-]+", version):
            raise ValueError("Invalid installed package identity")
        identity = (name, architecture)
        if identity in packages:
            raise ValueError("Duplicate installed package identity")
        packages[identity] = item
    listed = {}
    for line in inventory.decode("utf-8").splitlines():
        name, version, architecture = line.split("\t")
        name = name.removesuffix(":" + architecture)
        identity = (name, architecture)
        if identity in listed:
            raise ValueError("Duplicate embedded inventory identity")
        listed[identity] = version
    if listed != {identity: item["Version"] for identity, item in packages.items()}:
        raise ValueError("Installed package status differs from embedded inventory")
    return sorted(packages.values(), key=lambda item: (item["Package"], item["Architecture"]))


def create(entries, metadata, apk_sha, rootfs_sha):
    packages = installed(entries, metadata["developer"]["packageInventorySha256"])
    if len(packages) != metadata["developer"]["installedPackageCount"]:
        raise ValueError("Installed package count differs from runtime metadata")
    texts, records, sources, missing = {}, [], {}, []
    for item in packages:
        name, version, architecture = item["Package"], item["Version"], item["Architecture"]
        source = re.fullmatch(r"([a-z0-9][a-z0-9+.-]*)(?: \(([A-Za-z0-9.+:~_-]+)\))?", item.get("Source", name))
        if not source:
            raise ValueError("Invalid source identity")
        source_name, source_version = source[1], source[2] or version
        reference = "SPDXRef-Debian-" + sha(f"{name}\x00{architecture}\x00{version}".encode())[:24]
        path = DOCS + name + "/copyright"
        record = {"SPDXID": reference, "name": name, "versionInfo": version, "supplier": "Organization: Debian",
                  "downloadLocation": "NOASSERTION", "filesAnalyzed": False, "licenseConcluded": "NOASSERTION",
                  "licenseDeclared": "NOASSERTION", "copyrightText": "NOASSERTION",
                  "sourceInfo": f"dpkg source identity: {source_name} ({source_version}); source archives not collected by this tool.",
                  "externalRefs": [{"referenceCategory": "PACKAGE-MANAGER", "referenceType": "purl",
                                    "referenceLocator": f"pkg:deb/debian/{quote(name, safe='')}@{quote(version, safe='')}?arch={architecture}&distro=debian-13"}]}
        try:
            canonical, data = resolve(entries, path)
            texts[path] = data
            texts[canonical] = data
            record["licenseComments"] = f"Verbatim text: {path}; resolved from {canonical}; SHA256={sha(data)}. NOASSERTION is not a license approval."
        except ValueError as failure:
            missing.append({"package": name, "path": path, "reason": str(failure)})
            record["licenseComments"] = "Copyright text unresolved; see coverage report."
        records.append(record)
        sources.setdefault((source_name, source_version), []).append(reference)
    for name in sorted(entries):
        if name.startswith(COMMON) and entries[name][0] in ("file", "hard", "symbolic"):
            _, data = resolve(entries, name)
            texts[name] = data
    created = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    root_id = "SPDXRef-DebianRootfs"
    root = {"SPDXID": root_id, "name": "opencode-android-debian-rootfs", "versionInfo": rootfs_sha,
            "downloadLocation": "NOASSERTION", "filesAnalyzed": False, "licenseDeclared": "NOASSERTION", "licenseConcluded": "NOASSERTION",
            "copyrightText": "NOASSERTION", "checksums": [{"algorithm": "SHA256", "checksumValue": rootfs_sha}],
            "sourceInfo": "Actual embedded assets/runtime/debian.pack; APK SHA256=" + apk_sha}
    document = {"spdxVersion": "SPDX-2.3", "dataLicense": "CC0-1.0", "SPDXID": "SPDXRef-DOCUMENT",
                "name": "OpenCode Android Debian component inventory",
                "creationInfo": {"creators": ["Tool: opencode-android-debian-sbom-1"], "created": created},
                "comment": "Debian portion only. Maven, Bun/OpenCode embedded dependencies and native source/license review remain separate. Source requests are not corresponding-source archives.",
                "packages": [root] + records,
                "relationships": [{"spdxElementId": "SPDXRef-DOCUMENT", "relationshipType": "DESCRIBES", "relatedSpdxElement": root_id}] +
                                 [{"spdxElementId": root_id, "relationshipType": "CONTAINS", "relatedSpdxElement": item["SPDXID"]} for item in records]}
    document["documentNamespace"] = "https://spdx.org/spdxdocs/opencode-android-debian-" + sha(json.dumps(document, sort_keys=True).encode())
    requests = [{"name": name, "version": version, "binarySpdxIds": ids, "archiveStatus": "not-collected"} for (name, version), ids in sorted(sources.items())]
    coverage = {"scope": "Debian portion only", "apkSha256": apk_sha, "rootfsSha256": rootfs_sha,
                "installedPackages": len(records), "copyrightResolved": len(records) - len(missing), "missingCopyright": missing,
                "licenseTextFiles": len(texts), "sourcePackageIdentities": len(requests), "sourceArchivesCollected": 0,
                "fullReleaseAuditComplete": False}
    return document, coverage, requests, texts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("artifacts"))
    args = parser.parse_args()
    with args.apk.open("rb") as stream:
        apk_sha = hashlib.file_digest(stream, "sha256").hexdigest()
    with zipfile.ZipFile(args.apk) as apk:
        if apk.getinfo("assets/runtime/pack.json").file_size > 65536:
            raise ValueError("Oversized runtime metadata")
        metadata = json.loads(apk.read("assets/runtime/pack.json"))
        if metadata["archive"] != "debian.pack" or apk.getinfo("assets/runtime/debian.pack").file_size > 2 * 1024**3:
            raise ValueError("Invalid Debian runtime archive")
        with apk.open("assets/runtime/debian.pack") as stream:
            checked = HashReader(stream)
            entries = collect(checked)
            while checked.read(1024 * 1024):
                pass
            rootfs_sha = checked.digest.hexdigest()
        if rootfs_sha != metadata["sha256"]:
            raise ValueError("Actual rootfs archive hash mismatch")
    document, coverage, requests, texts = create(entries, metadata, apk_sha, rootfs_sha)
    args.output.mkdir(parents=True, exist_ok=True)
    outputs = {"debian-components.spdx.json": document, "debian-inventory-coverage.json": coverage, "debian-source-requests.json": requests}
    names = list(outputs) + ["debian-license-texts.zip"]
    if any((args.output / name).exists() for name in names):
        raise FileExistsError("Inventory output already exists; select a fresh output directory")
    for name, value in outputs.items():
        with (args.output / name).open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2); stream.write("\n")
    with zipfile.ZipFile(args.output / "debian-license-texts.zip", "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(texts.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    print(json.dumps(coverage, ensure_ascii=False))
    if coverage["missingCopyright"]:
        raise SystemExit("Incomplete Debian copyright coverage")


if __name__ == "__main__":
    main()
