#!/usr/bin/env python3
"""Build the pinned, unmodified official CLI/WebUI pair on Linux or Windows."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def checked(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, **kwargs)


def build_environment(base, lock, abi, bun, epoch):
    """No inherited crash/source-upload or OTLP configuration in the upstream build."""
    if lock.get("sentryDsn") != "unset":
        raise RuntimeError("The Android build requires the upstream Sentry DSN to remain unset")
    environment = {key: value for key, value in base.items()
                   if not key.upper().startswith(("SENTRY_", "VITE_SENTRY_", "OTEL_"))}
    environment.update(OPENCODE_CHANNEL=lock["channel"], OPENCODE_VERSION=lock["buildVersion"],
                       OPENCODE_TARGET="linux-arm64" if abi == "arm64-v8a" else "linux-x64",
                       VITE_SENTRY_DSN="", SOURCE_DATE_EPOCH=epoch)
    for key in ("OPENCODE_MODELS_SNAPSHOT", "OPENCODE_RELEASE_NOTES", "VITE_OPENCODE_TEST_FIXTURES"):
        environment.pop(key, None)
    environment["PATH"] = str(Path(bun).parent) + os.pathsep + environment.get("PATH", "")
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("abi", choices=("arm64-v8a", "x86_64"), nargs="?", default="arm64-v8a")
    parser.add_argument("--bun", default=shutil.which("bun") or shutil.which("bun.exe"))
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--no-bundle", action="store_true", help="build and validate without replacing APK runtime assets")
    args = parser.parse_args()
    lock = json.loads((ROOT / "runtime/opencode-dev.lock.json").read_text())
    source = ROOT / ".cache/opencode-upstream"
    if not (source / ".git").exists():
        if source.exists() and any(source.iterdir()):
            raise RuntimeError("Unrecognized source directory was preserved")
        checked("git", "init", source)
        checked("git", "-C", source, "remote", "add", "origin", lock["repository"])
        checked("git", "-C", source, "fetch", "--depth=1", "origin", lock["commit"])
        checked("git", "-C", source, "checkout", "--detach", lock["commit"])
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != lock["commit"]:
        raise RuntimeError("Source commit differs from the lock; existing files were preserved")
    dirty = subprocess.check_output(["git", "-C", str(source), "status", "--porcelain", "--untracked-files=no"], text=True)
    extra = subprocess.check_output(["git", "-C", str(source), "ls-files", "--others", "--exclude-standard"], text=True).splitlines()
    extra = [name for name in extra if not name.startswith(("packages/cli/.bun-cache/", "packages/cli/dist-x64/"))]
    if dirty or extra:
        raise RuntimeError("Official source has local changes; build refused without modifying them")
    if not args.bun:
        parser.error("install the locked Bun version or pass --bun")
    bun = str(Path(args.bun).resolve())
    version = subprocess.check_output([bun, "--version"], text=True).strip()
    if version != lock["bunVersion"]:
        raise RuntimeError(f"Bun {lock['bunVersion']} is required; found {version}")
    epoch = subprocess.check_output(["git", "-C", str(source), "show", "-s", "--format=%ct", "HEAD"], text=True).strip()
    environment = build_environment(os.environ, lock, args.abi, bun, epoch)
    output = ROOT / ".cache/opencode-build" / args.abi
    # Upstream removes --outdir recursively. Only hand it this dedicated cache descendant.
    resolved = output.resolve()
    if not resolved.is_relative_to((ROOT / ".cache/opencode-build").resolve()) or output.is_symlink():
        raise RuntimeError("Unsafe build output path")
    if not args.skip_install:
        checked(bun, "install", "--frozen-lockfile", "--os=*", "--cpu=*", "--libc=*", cwd=source, env=environment)
    checked(bun, "run", "--cwd", "packages/cli", "script/build.ts", "--skip-install", f"--outdir={resolved}", cwd=source, env=environment)
    binary = output / ("cli-linux-arm64" if args.abi == "arm64-v8a" else "cli-linux-x64") / "bin/opencode"
    if args.abi == "arm64-v8a":
        checked(sys.executable, ROOT / "tools/check_elf.py", binary)
    if not args.no_bundle:
        checked(sys.executable, ROOT / "tools/bundle_opencode.py", args.abi, binary)
    print(f"Official pair built: {lock['buildVersion']} ({args.abi})", flush=True)


if __name__ == "__main__":
    main()
