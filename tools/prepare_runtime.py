"""Fetch Debian's pinned official OCI layer, verify content digests and bundle it in the APK.

No guest executable is run during packaging. No floating tag or 'latest' URL is used.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import shutil
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCES = {
    "arm64-v8a": ("9c4818e3bfd35139d3798b0420e9c46836658b2e", "arm64"),
    "x86_64": ("cee78df586385250331cacba8fd1140133c054e5", "amd64"),
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def prepare(abi):
    commit, arch = SOURCES[abi]
    base = f"https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/{commit}/trixie/slim/oci"
    index = json.loads(fetch(f"{base}/index.json"))
    descriptor = index["manifests"][0]
    assert descriptor["platform"]["os"] == "linux" and descriptor["platform"]["architecture"] == arch
    manifest_bytes = base64.b64decode(descriptor["data"])
    assert "sha256:" + hashlib.sha256(manifest_bytes).hexdigest() == descriptor["digest"]
    layers = json.loads(manifest_bytes)["layers"]
    assert len(layers) == 1
    layer = layers[0]
    expected = layer["digest"].removeprefix("sha256:")
    cache = ROOT / ".cache" / "downloads" / f"debian-{expected}.tar.gz"
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        content = fetch(f"{base}/blobs/rootfs.tar.gz")
        assert len(content) == layer["size"]
        assert hashlib.sha256(content).hexdigest() == expected
        cache.write_bytes(content)
    assert hashlib.file_digest(cache.open("rb"), "sha256").hexdigest() == expected
    output = ROOT / "app" / "runtime-assets" / abi / "runtime"
    output.mkdir(parents=True, exist_ok=True)
    pack = {
        "format": 1, "abi": abi, "suite": "trixie", "archive": "debian.pack",
        "sha256": expected, "minimumFreeBytes": 512 * 1024 * 1024,
        "source": f"https://github.com/debuerreotype/docker-debian-artifacts/tree/{commit}/trixie/slim",
        "imageManifestDigest": descriptor["digest"],
    }
    lock = ROOT / "runtime" / f"debian-{abi}.json"
    lock.parent.mkdir(exist_ok=True)
    lock.write_text(json.dumps(pack, indent=2) + "\n", encoding="utf-8")
    existing = output / "pack.json"
    if existing.exists() and json.loads(existing.read_text()).get("developer"):
        print(f"Verified {abi} base source; existing developer pack preserved", flush=True)
        return
    # aapt strips a .gz suffix and inflates it; preserve exact bytes as a .pack asset.
    shutil.copyfile(cache, output / "debian.pack")
    old = output / "rootfs.tar.gz"
    if old.exists(): old.unlink()
    existing.write_text(json.dumps(pack, indent=2) + "\n", encoding="utf-8")
    print(f"Bundled {abi}: {expected}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("abi", choices=SOURCES, nargs="+", default=["arm64-v8a"])
    args = parser.parse_args()
    for abi in args.abi:
        prepare(abi)
