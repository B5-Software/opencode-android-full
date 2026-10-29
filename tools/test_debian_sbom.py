"""Verify archive-only license resolution, inventory fidelity and non-overwriting output behavior."""
import copy
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
import debian_sbom as sbom

ROOT = Path(__file__).resolve().parents[1]


def fixture():
    inventory = b"alpha:arm64\t1:1.2+b1\tarm64\nbeta\t1:1.2+b2\tall\n"
    status = ("Package: alpha\nStatus: install ok installed\nArchitecture: arm64\nVersion: 1:1.2+b1\nSource: shared-source (1:1.2)\n\n"
              "Package: beta\nStatus: install ok installed\nArchitecture: all\nVersion: 1:1.2+b2\nSource: shared-source (1:1.2)\n\n"
              "Package: removed\nStatus: deinstall ok config-files\nArchitecture: all\nVersion: 1\n").encode()
    entries = {sbom.STATUS: ("file", status), sbom.INVENTORY: ("file", inventory),
               "usr/share/doc/alpha/copyright": ("file", "Fixture copyright 作者\nLicense: GPL-2+\n".encode()),
               "usr/share/doc/beta": ("symbolic", "alpha"), "usr/share/common-licenses/GPL-2": ("file", b"Fixture common license\n")}
    metadata = {"archive": "debian.pack", "developer": {"packageInventorySha256": sbom.sha(inventory), "installedPackageCount": 2}}
    return entries, metadata


def archive(entries):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as writer:
        for name, (kind, value) in entries.items():
            info = tarfile.TarInfo(name)
            if kind == "file":
                info.size = len(value); writer.addfile(info, io.BytesIO(value))
            else:
                info.type = tarfile.LNKTYPE if kind == "hard" else tarfile.SYMTYPE
                info.linkname = value; writer.addfile(info)
    return output.getvalue()


class InventoryTest(unittest.TestCase):
    def test_shared_copyright_multiarch_source_version_and_removed_package_are_preserved(self):
        entries, metadata = fixture()
        document, coverage, sources, texts = sbom.create(sbom.collect(io.BytesIO(archive(entries))), metadata, "a" * 64, "b" * 64)
        self.assertEqual(2, coverage["installedPackages"])
        self.assertEqual(2, coverage["copyrightResolved"])
        self.assertFalse(coverage["fullReleaseAuditComplete"])
        self.assertEqual(0, coverage["sourceArchivesCollected"])
        self.assertEqual([(source["name"], source["version"], len(source["binarySpdxIds"])) for source in sources], [("shared-source", "1:1.2", 2)])
        self.assertEqual(texts["usr/share/doc/alpha/copyright"], texts["usr/share/doc/beta/copyright"])
        self.assertEqual(entries["usr/share/doc/alpha/copyright"][1], texts["usr/share/doc/alpha/copyright"])
        self.assertEqual(3, len(document["packages"]))
        self.assertEqual({"NOASSERTION"}, {item["licenseConcluded"] for item in document["packages"]})
        self.assertIn("1%3A1.2%2Bb1", document["packages"][1]["externalRefs"][0]["referenceLocator"])

    def test_relative_absolute_and_archive_root_hardlinks_resolve_without_host_access(self):
        entries, _ = fixture()
        entries["usr/share/doc/gamma/copyright"] = ("hard", "./usr/share/doc/alpha/copyright")
        entries["usr/share/doc/delta"] = ("symbolic", "/usr/share/doc/alpha")
        expected = entries["usr/share/doc/alpha/copyright"][1]
        collected = sbom.collect(io.BytesIO(archive(entries)))
        for path in ("usr/share/doc/beta/copyright", "usr/share/doc/gamma/copyright", "usr/share/doc/delta/copyright"):
            self.assertEqual(expected, sbom.resolve(collected, path)[1])

    def test_cycles_escaping_links_and_non_document_targets_are_rejected(self):
        entries, _ = fixture()
        for target in ("beta", "../../../../../../etc/passwd", "/etc/passwd"):
            changed = dict(entries); changed["usr/share/doc/beta"] = ("symbolic", target)
            with self.assertRaises(ValueError): sbom.resolve(changed, "usr/share/doc/beta/copyright")
        changed = dict(entries); changed["usr/share/doc/alpha/copyright"] = ("other", None)
        with self.assertRaises(ValueError): sbom.resolve(changed, "usr/share/doc/alpha/copyright")

    def test_hash_inventory_and_package_count_drift_are_rejected(self):
        entries, metadata = fixture()
        changed = copy.deepcopy(metadata); changed["developer"]["packageInventorySha256"] = "0" * 64
        with self.assertRaises(ValueError): sbom.create(entries, changed, "a" * 64, "b" * 64)
        changed = copy.deepcopy(metadata); changed["developer"]["installedPackageCount"] = 3
        with self.assertRaises(ValueError): sbom.create(entries, changed, "a" * 64, "b" * 64)
        changed = dict(entries); changed[sbom.STATUS] = ("file", entries[sbom.STATUS][1].replace(b"1:1.2+b1", b"1:1.2+b9"))
        with self.assertRaises(ValueError): sbom.create(changed, metadata, "a" * 64, "b" * 64)

    def test_missing_copyright_is_visible_and_never_labeled_complete(self):
        entries, metadata = fixture(); del entries["usr/share/doc/alpha/copyright"]
        _, coverage, _, _ = sbom.create(entries, metadata, "a" * 64, "b" * 64)
        self.assertEqual(2, len(coverage["missingCopyright"]))
        self.assertEqual(0, coverage["copyrightResolved"])
        self.assertFalse(coverage["fullReleaseAuditComplete"])

    def test_member_traversal_and_oversized_metadata_are_rejected(self):
        for name in ("../outside", "/absolute", "usr/../../outside", "usr\\bad"):
            with self.assertRaises(ValueError): sbom.member_name(name)
        for name, size in (("usr/share/doc/alpha/copyright", sbom.MAX_FILE + 1), ("usr/lib/fixture", sbom.MAX_ENTRY + 1)):
            info = tarfile.TarInfo(name); info.size = size
            with self.assertRaises(ValueError): sbom.collect(io.BytesIO(info.tobuf() + bytes(1024)))


class ApkIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(tempfile.mkdtemp(prefix="sbom-test-", dir=ROOT / ".cache")).resolve()
        self.assertEqual((ROOT / ".cache").resolve(), self.directory.parent)

    def tearDown(self):
        self.assertEqual((ROOT / ".cache").resolve(), self.directory.parent)
        shutil.rmtree(self.directory)

    def run_tool(self, bad_hash=False):
        entries, metadata = fixture(); data = archive(entries)
        metadata["sha256"] = "0" * 64 if bad_hash else sbom.sha(data)
        candidate = self.directory / "fixture.apk"
        with zipfile.ZipFile(candidate, "w") as apk:
            apk.writestr("assets/runtime/pack.json", json.dumps(metadata))
            apk.writestr("assets/runtime/debian.pack", data)
        return subprocess.run([sys.executable, str(ROOT / "tools/debian_sbom.py"), "--apk", str(candidate), "--output", str(self.directory / "output")], capture_output=True, text=True)

    def test_actual_zip_scan_retains_bytes_and_refuses_output_overwrite(self):
        self.assertEqual(0, self.run_tool().returncode)
        output = self.directory / "output"
        before = (output / "debian-components.spdx.json").read_bytes()
        # The complete request file is locked by SHA-256 across Windows/Linux.
        for name in ("debian-components.spdx.json", "debian-inventory-coverage.json", "debian-source-requests.json"):
            data = (output / name).read_bytes()
            self.assertNotIn(b"\r", data)
            self.assertEqual((json.dumps(json.loads(data), ensure_ascii=False, indent=2) + "\n").encode("utf-8"), data)
        with zipfile.ZipFile(output / "debian-license-texts.zip") as texts:
            self.assertEqual(fixture()[0]["usr/share/doc/alpha/copyright"][1], texts.read("usr/share/doc/beta/copyright"))
        self.assertNotEqual(0, self.run_tool().returncode)
        self.assertEqual(before, (output / "debian-components.spdx.json").read_bytes())

    def test_rootfs_hash_failure_writes_no_inventory(self):
        self.assertNotEqual(0, self.run_tool(bad_hash=True).returncode)
        self.assertFalse((self.directory / "output").exists())


if __name__ == "__main__":
    unittest.main()
