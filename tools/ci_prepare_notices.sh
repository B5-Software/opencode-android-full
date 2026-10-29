#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p .cache artifacts
delivery="$(mktemp -d "$ROOT/.cache/notice-delivery.XXXXXX")"
bash gradlew exportReleaseArtifactInputs --no-daemon --console=plain
python3 tools/license_inputs.py --source "$ROOT/.cache/opencode-notice-inputs"
python3 tools/maven_materials.py --output "$delivery/maven"
python3 tools/npm_materials.py --source "$ROOT/.cache/opencode-notice-inputs" --output "$delivery/npm"
mkdir -p app/build/generated/dependency-notices/licenses/dependencies
cp "$delivery/maven/maven-license-texts.zip" "$delivery/npm/npm-license-texts.zip" app/build/generated/dependency-notices/licenses/dependencies/
cp "$delivery/maven/"* "$delivery/npm/"* artifacts/
# Only the dedicated notice checkout in this ephemeral Linux runner is removed.
# Local builds preserve their caches and source checkouts.
if [[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux && "${GITHUB_WORKSPACE:-}" == "$ROOT" ]]; then
    notice_source="$(realpath .cache/opencode-notice-inputs)"
    [[ "$notice_source" == "$ROOT/.cache/opencode-notice-inputs" ]] || exit 1
    rm -rf -- "$notice_source"
    if [[ "${BUN_INSTALL_CACHE_DIR:-}" == "${RUNNER_TEMP:?}/opencode-notice-bun-cache" && -d "$BUN_INSTALL_CACHE_DIR" ]]; then
        notice_cache="$(realpath "$BUN_INSTALL_CACHE_DIR")"
        [[ "$notice_cache" == "$RUNNER_TEMP/opencode-notice-bun-cache" ]] || exit 1
        rm -rf -- "$notice_cache"
    fi
fi
