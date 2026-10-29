#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
python3 tools/devkit_mirror.py indexes
python3 tools/debian_sources.py keyring --apk artifacts/opencode-android-full-arm64-unsigned.apk --output .cache/debian-source-keyring.pgp
python3 tools/debian_sources.py plan --inventory artifacts/debian-unsigned --keyring .cache/debian-source-keyring.pgp --expected-lock runtime/debian-source-archive-lock.json --output artifacts/debian-source-archive-lock.json
python3 tools/debian_sources.py collect --lock artifacts/debian-source-archive-lock.json --objects .cache/debian-source-objects --report artifacts/debian-source-collection.json --workers 4
python3 tools/debian_sources.py package --lock artifacts/debian-source-archive-lock.json --objects .cache/debian-source-objects --inventory artifacts/debian-unsigned --keyring .cache/debian-source-keyring.pgp --output .cache/debian-source-delivery
cp .cache/debian-source-delivery/* artifacts/
