#!/usr/bin/env python3
"""Emit explicit, replayable APK version inputs; never reads signing secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True, cwd=ROOT).strip()
    tag = args.tag
    if tag:
        match = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?", tag)
        if not match:
            raise ValueError("Release tags must use vMAJOR.MINOR.PATCH with an optional prerelease")
        major, minor, patch = map(int, match.group(1, 2, 3))
        if major > 209 or minor > 999 or patch > 999:
            raise ValueError("Release version exceeds the Android versionCode allocation")
        code = major * 10_000_000 + minor * 10_000 + patch * 10 + (0 if match.group(4) else 9)
        version = tag[1:]
    else:
        version = "0.1.0-dev." + sha[:12]
        code = 10_000
    if code <= 0:
        raise ValueError("Android versionCode must be positive")
    locked = json.loads((ROOT / "runtime/opencode-dev.lock.json").read_text())
    info = {"sourceCommit": sha, "versionName": version, "versionCode": code, "openCode": locked,
            "runtimePack": json.loads((ROOT / "app/runtime-assets/arm64-v8a/runtime/pack.json").read_text()),
            "targetSdk": 36, "minSdk": 29, "abi": "arm64-v8a", "sourceDateEpoch": int(subprocess.check_output(["git", "show", "-s", "--format=%ct", "HEAD"], text=True, cwd=ROOT))}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"version_name={version}\nversion_code={code}\n")
    print(f"Build {version}, Android code {code}, source {sha}")


if __name__ == "__main__":
    main()
