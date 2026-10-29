#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 tools/prepare_runtime.py arm64-v8a
python3 tools/prepare_terminal.py
python3 tools/build_opencode.py arm64-v8a
bash native-host/build.sh arm64-v8a
python3 tools/devkit_mirror.py indexes
python3 tools/devkit_mirror.py packages --locked
for phase in create install export; do
    sudo env DEVKIT_BUILD_DIR="$ROOT/.cache/ci-developer-build" bash tools/devkit_rootfs.sh "$phase"
done
python3 tools/bundle_developer.py
python3 tools/verify_ci_assets.py
