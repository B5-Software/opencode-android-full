"""Bundle the clean, signed-index Debian developer build without exporting device data."""
import hashlib
import json
from pathlib import Path
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    archive = ROOT / ".cache/debian-developer-arm64.tar.gz"
    inventory = ROOT / "runtime/developer-packages-arm64.tsv"
    profile = json.loads((ROOT / "runtime/developer-profile.json").read_text())
    assert archive.is_file() and inventory.is_file()
    packages = {line.split("\t")[0].split(":")[0] for line in inventory.read_text().splitlines()}
    assert set(profile["packages"]) <= packages
    size = 0
    sizes = {}
    aliases = []
    with tarfile.open(archive, "r|gz") as members:
        for entry in members:
            assert entry.size <= 512 * 1024 * 1024
            if entry.isfile():
                size += entry.size
                sizes[entry.name.removeprefix("./")] = entry.size
            elif entry.islnk():
                aliases.append(entry.linkname.removeprefix("./"))
    # Android SELinux may require materializing archive hard links as copies.
    size += sum(sizes[name] for name in aliases)
    assert size < 4 * 1024**3
    output = ROOT / "app/runtime-assets/arm64-v8a/runtime"
    pack = json.loads((output / "pack.json").read_text())
    base = json.loads((ROOT / "runtime/debian-arm64-v8a.json").read_text())
    pack.update({
        "sha256": digest(archive),
        "minimumFreeBytes": size + pack.get("opencode", {}).get("size", 0) + 512 * 1024**2,
        "unpackedBytes": size,
        "baseImageSha256": base["sha256"],
        "developer": {
            "profile": profile["id"], "snapshot": profile["snapshot"],
            "packages": profile["packages"], "installedPackageCount": len(packages),
            "packageInventorySha256": digest(inventory),
            "packageArchiveLockSha256": digest(ROOT / "runtime/developer-deb-lock.json"),
        },
    })
    shutil.copyfile(archive, output / "debian.pack")
    content = json.dumps(pack, indent=2) + "\n"
    (output / "pack.json").write_text(content, encoding="utf-8")
    (ROOT / "runtime/debian-developer-arm64.json").write_text(content, encoding="utf-8")
    print(f"Bundled {len(packages)} packages; {size / 1024**3:.2f} GiB unpacked; SHA256 {pack['sha256']}", flush=True)


if __name__ == "__main__":
    main()
