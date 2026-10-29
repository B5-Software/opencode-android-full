#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${ANDROID_KEYSTORE_BASE64:?Configure the release environment signing secrets}"
: "${ANDROID_KEYSTORE_PASSWORD:?Missing store password}"
: "${ANDROID_KEY_ALIAS:?Missing key alias}"
: "${ANDROID_KEY_PASSWORD:?Missing key password}"
: "${ANDROID_HOME:?Missing Android SDK}"
umask 077
store="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/opencode-signing.XXXXXX")"
trap 'rm -f -- "$store"' EXIT
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$store"
signer="$ANDROID_HOME/build-tools/36.0.0/apksigner"
java tools/SignUpdate.java "$store" artifacts/opencode-update-manifest.json artifacts/opencode-update-manifest.sig
python3 tools/inject_core_proof.py --unsigned-apk artifacts/opencode-android-full-arm64-unsigned.apk --manifest artifacts/opencode-update-manifest.json --signature artifacts/opencode-update-manifest.sig --output artifacts/opencode-with-proof-unsigned.apk
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -P 16 -f 4 artifacts/opencode-with-proof-unsigned.apk artifacts/opencode-with-proof-aligned.apk
"$signer" sign --ks "$store" --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
    --out artifacts/opencode-android-full-arm64.apk artifacts/opencode-with-proof-aligned.apk
"$signer" verify --verbose --print-certs artifacts/opencode-android-full-arm64.apk > artifacts/signing-certificate.txt
java --class-path "$ANDROID_HOME/build-tools/36.0.0/lib/apksigner.jar" tools/VerifyCoreProof.java artifacts/opencode-android-full-arm64.apk
python3 tools/check_apk.py artifacts/opencode-android-full-arm64.apk --abi arm64-v8a --require-core-proof --require-license-materials
python3 tools/package_update.py --manifest artifacts/opencode-update-manifest.json --signature artifacts/opencode-update-manifest.sig --payload artifacts/opencode-update.pack --output artifacts/opencode-android-full-arm64.ocaupdate
python3 tools/update_feed.py prepare --package artifacts/opencode-android-full-arm64.ocaupdate --repository "${GITHUB_REPOSITORY:?Missing release repository}" --tag "${GITHUB_REF_NAME:?Missing release tag}" --output artifacts/opencode-feed.json
java tools/SignUpdate.java "$store" artifacts/opencode-feed.json artifacts/opencode-feed.sig
python3 tools/update_feed.py frame --body artifacts/opencode-feed.json --signature artifacts/opencode-feed.sig --output artifacts/opencode-android-full-arm64.ocafeed
python3 tools/debian_sbom.py --apk artifacts/opencode-android-full-arm64.apk --output artifacts/debian-signed
cp artifacts/debian-signed/* artifacts/
(cd artifacts && sha256sum opencode-android-full-arm64.apk opencode-android-full-arm64.ocaupdate opencode-android-full-arm64.ocafeed signing-certificate.txt build-info.json opencode-android-full-source.tar.gz ci-native-sources.tar.gz THIRD_PARTY_NOTICES.md developer-packages-arm64.tsv developer-deb-lock.json gradle-runtime-dependencies.txt debian-components.spdx.json debian-inventory-coverage.json debian-source-requests.json debian-license-texts.zip debian-source-archive-lock.json debian-source-collection.json debian-source-delivery.json debian-source-part-*.tar.gz > SHA256SUMS-signed.txt)
echo 'Signed APK verified; private keystore remains outside artifacts.'
(cd artifacts && sha256sum bun-source.tar.gz bun-tinycc-source.tar.gz bun-webkit-source.tar.gz opencode-source.tar.gz upstream-source-delivery.json upstream-license-texts.zip >> SHA256SUMS-signed.txt)
(cd artifacts && sha256sum maven-source-delivery.json maven-sources.zip maven-license-texts.zip npm-notice-inventory.json npm-components.spdx.json npm-license-texts.zip >> SHA256SUMS-signed.txt)
