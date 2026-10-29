"""Bundle exact npm terminal libraries; verify SRI and never load a runtime CDN."""
import base64
import hashlib
import io
import json
import pathlib
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGES = {
    "@xterm/xterm": ("5.5.0", {"lib/xterm.js": "xterm.js", "css/xterm.css": "xterm.css", "LICENSE": "LICENSE-xterm"}),
    "@xterm/addon-fit": ("0.10.0", {"lib/addon-fit.js": "addon-fit.js", "LICENSE": "LICENSE-addon-fit"}),
}
lock_file = ROOT / "runtime/terminal.lock.json"
lock = json.loads(lock_file.read_text()) if lock_file.exists() else {}
output = ROOT / "app/src/main/assets/terminal/vendor"
output.mkdir(parents=True, exist_ok=True)
for name, (version, files) in PACKAGES.items():
    metadata = json.load(urllib.request.urlopen(f"https://registry.npmjs.org/{name}/{version}", timeout=30))
    record = {"version": version, "integrity": metadata["dist"]["integrity"], "tarball": metadata["dist"]["tarball"]}
    if name in lock:
        assert lock[name] == record, f"Registry metadata changed for locked {name}"
    algorithm, expected = record["integrity"].split("-", 1)
    assert algorithm == "sha512"
    data = urllib.request.urlopen(record["tarball"], timeout=60).read()
    assert base64.b64encode(hashlib.sha512(data).digest()).decode() == expected
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for source, destination in files.items():
            (output / destination).write_bytes(archive.extractfile("package/" + source).read())
    lock[name] = record
    print(f"Bundled {name}@{version} (SRI checked)")
lock_file.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
