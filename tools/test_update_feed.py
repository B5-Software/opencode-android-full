import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import update_feed


class UpdateFeedTest(unittest.TestCase):
    def temporary(self):
        root = Path(__file__).resolve().parents[1] / ".cache"
        root.mkdir(exist_ok=True)
        return tempfile.TemporaryDirectory(prefix="update-feed-test-", dir=root)
    def package(self, root):
        path = Path(root) / "opencode-android-full-arm64.ocaupdate"
        manifest = {"format": 1, "kind": "opencode-pair", "abi": "arm64-v8a", "version": "2.1.4-dev.abc",
                    "commit": "a" * 40, "minHostVersionCode": 1, "binary": {"sha256": "b" * 64}}
        body = json.dumps(manifest).encode()
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("manifest.json", body); archive.writestr("manifest.sig", b"public-test-signature")
            archive.writestr("opencode.pack", b"test-only-not-executable")
        return path, body

    def test_feed_binds_package_and_proof_and_exact_release_url(self):
        with self.temporary() as directory:
            self.assertEqual(Path(__file__).resolve().parents[1] / ".cache", Path(directory).resolve().parent)
            package, manifest = self.package(directory)
            body = update_feed.prepare(package, "owner/example", "v0.1.0-preview.1")
            record = json.loads(body)
            self.assertEqual(hashlib.sha256(package.read_bytes()).hexdigest(), record["packageSha256"])
            self.assertEqual(hashlib.sha256(manifest).hexdigest(), record["manifestSha256"])
            self.assertEqual(package.stat().st_size, record["packageSize"])
            self.assertEqual("https://github.com/owner/example/releases/download/v0.1.0-preview.1/opencode-android-full-arm64.ocaupdate", record["packageUrl"])
            framed = update_feed.frame(body, b"fixture-signature")
            self.assertEqual(b"OCAFEED1", framed[:8]); self.assertEqual(len(body), int.from_bytes(framed[8:12], "big"))
            self.assertEqual(body, framed[16:16 + len(body)])

    def test_untrusted_tag_repository_and_bounds_rejected(self):
        with self.temporary() as directory:
            self.assertEqual(Path(__file__).resolve().parents[1] / ".cache", Path(directory).resolve().parent)
            package, _ = self.package(directory)
            for repository, tag in [("owner/example", "v1.0.0/../x"), ("https://evil/x", "v1.0.0"), ("a/b?x", "v1.0.0"), ("a/b", "1.0.0")]:
                with self.assertRaises(ValueError): update_feed.prepare(package, repository, tag)
        for body, signature in [(b"", b"x"), (b"x" * 16385, b"x"), (b"x", b"x" * 8193)]:
            with self.assertRaises(ValueError): update_feed.frame(body, signature)


if __name__ == "__main__": unittest.main()
