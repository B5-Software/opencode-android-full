"""Integration checks for release version metadata and CI output boundaries."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BuildMetadataTest(unittest.TestCase):
    def setUp(self):
        cache = ROOT / ".cache"
        cache.mkdir(exist_ok=True)
        self.fixture = Path(tempfile.mkdtemp(prefix="ci-metadata-test-", dir=cache)).resolve()
        (self.fixture / "runtime").mkdir()
        (self.fixture / "runtime/opencode-dev.lock.json").write_text('{"commit":"fixture-upstream"}', encoding="utf-8")
        runtime = self.fixture / "app/runtime-assets/arm64-v8a/runtime"
        runtime.mkdir(parents=True)
        (runtime / "pack.json").write_text('{"fixture":true}', encoding="utf-8")
        for command in (("init",), ("add", "."), ("-c", "user.name=CI fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "Fixture only")):
            subprocess.run(["git", "-C", str(self.fixture), *command], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def tearDown(self):
        # Never use the host temp tree; validate the exact task-owned workspace descendant.
        self.assertEqual((ROOT / ".cache").resolve(), self.fixture.parent)
        # Git marks object files read-only on Windows. Keep these tiny, ignored
        # test fixtures there; Linux CI removes its own disposable fixture tree.
        if os.name != "nt": shutil.rmtree(self.fixture)

    def generate(self, tag=""):
        code = "import importlib.util, pathlib; s=importlib.util.spec_from_file_location('ci_info',__import__('sys').argv[1]); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); m.ROOT=pathlib.Path(__import__('sys').argv[2]); __import__('sys').argv=['ci_build_info','--tag',__import__('sys').argv[3],'--output','artifacts/build-info.json']; m.main()"
        environment = dict(os.environ, GITHUB_OUTPUT=str(self.fixture / "outputs"))
        result = subprocess.run([sys.executable, "-c", code, str(ROOT / "tools/ci_build_info.py"), str(self.fixture), tag], cwd=self.fixture, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if result.returncode: return result, None
        return result, json.loads((self.fixture / "artifacts/build-info.json").read_text())

    def test_source_commit_and_github_outputs_describe_actual_checkout(self):
        result, info = self.generate()
        self.assertEqual(0, result.returncode, result.stderr)
        expected = subprocess.check_output(["git", "-C", str(self.fixture), "rev-parse", "HEAD"], text=True).strip()
        self.assertEqual(expected, info["sourceCommit"])
        self.assertTrue(info["versionName"].endswith(expected[:12]))
        self.assertEqual(36, info["targetSdk"])
        output = (self.fixture / "outputs").read_text()
        self.assertIn("version_name=" + info["versionName"] + "\n", output)
        self.assertIn("version_code=" + str(info["versionCode"]) + "\n", output)

    def test_patch_and_stable_releases_increase_android_version_code(self):
        _, prerelease = self.generate("v1.2.3-rc.1")
        _, stable = self.generate("v1.2.3")
        _, patch = self.generate("v1.2.4")
        self.assertLess(prerelease["versionCode"], stable["versionCode"])
        self.assertLess(stable["versionCode"], patch["versionCode"])
        self.assertLess(patch["versionCode"], 2_100_000_000)

    def test_invalid_tag_cannot_inject_shell_or_github_output(self):
        for tag in ("v1.2.3;touch sentinel", "v1.2.3\nversion_code=1", "v01.2.3", "v210.0.0", "v1.1000.0", "v1.2.1000"):
            result, info = self.generate(tag)
            self.assertNotEqual(0, result.returncode)
            self.assertIsNone(info)
        self.assertFalse((self.fixture / "outputs").exists())
        self.assertFalse((self.fixture / "sentinel").exists())


class BuildPrivacyTest(unittest.TestCase):
    def environment(self, base, lock=None):
        import importlib.util
        spec = importlib.util.spec_from_file_location("build_opencode_fixture", ROOT / "tools/build_opencode.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.build_environment(base, lock or {"sentryDsn": "unset", "channel": "dev", "buildVersion": "fixture-version"},
                                        "arm64-v8a", str(ROOT / ".cache/tools/bun-windows-x64/bun.exe"), "123456")

    def test_build_does_not_inherit_source_upload_or_tracing_credentials(self):
        base = {"PATH": "fixture-path", "SENTRY_AUTH_TOKEN": "fixture-upload-token", "SENTRY_ORG": "fixture-org",
                "SENTRY_PROJECT": "fixture-project", "sentry_url": "https://fixture.invalid", "VITE_SENTRY_DSN": "fixture-dsn",
                "VITE_SENTRY_RELEASE": "fixture-release", "OTEL_EXPORTER_OTLP_ENDPOINT": "https://fixture.invalid",
                "OTEL_EXPORTER_OTLP_HEADERS": "fixture-header", "OPENCODE_MODELS_SNAPSHOT": "fixture-snapshot",
                "OPENCODE_RELEASE_NOTES": "fixture-notes", "VITE_OPENCODE_TEST_FIXTURES": "1"}
        original = dict(base)
        result = self.environment(base)
        self.assertEqual(original, base)
        self.assertEqual("", result["VITE_SENTRY_DSN"])
        self.assertFalse(any(key.upper().startswith(("SENTRY_", "OTEL_")) for key in result))
        self.assertEqual(["VITE_SENTRY_DSN"], [key for key in result if key.upper().startswith("VITE_SENTRY_")])
        for key in ("OPENCODE_MODELS_SNAPSHOT", "OPENCODE_RELEASE_NOTES", "VITE_OPENCODE_TEST_FIXTURES"):
            self.assertNotIn(key, result)

    def test_locked_build_identity_and_package_download_environment_are_preserved(self):
        result = self.environment({"PATH": "fixture-path", "HTTPS_PROXY": "http://fixture.invalid:1234", "TEMP": "fixture-temp",
                                   "OPENCODE_CHANNEL": "untrusted-channel", "OPENCODE_TARGET": "wrong-target"})
        self.assertEqual("dev", result["OPENCODE_CHANNEL"])
        self.assertEqual("fixture-version", result["OPENCODE_VERSION"])
        self.assertEqual("linux-arm64", result["OPENCODE_TARGET"])
        self.assertEqual("123456", result["SOURCE_DATE_EPOCH"])
        self.assertTrue(result["PATH"].endswith(os.pathsep + "fixture-path"))
        self.assertEqual("http://fixture.invalid:1234", result["HTTPS_PROXY"])
        self.assertEqual("fixture-temp", result["TEMP"])

    def test_telemetry_enabled_lock_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.environment({}, {"sentryDsn": "https://fixture.invalid", "channel": "dev", "buildVersion": "fixture"})


if __name__ == "__main__":
    unittest.main()
