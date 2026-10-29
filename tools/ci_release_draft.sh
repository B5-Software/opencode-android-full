#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
: "${RELEASE_TAG:?Missing release tag}"
: "${GITHUB_REPOSITORY:?Missing repository}"
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]] || exit 2
cat > artifacts/RELEASE_NOTES.md <<'NOTES'
ARM64 Android engineering prerelease. Built from tagged source and pinned Debian/OpenCode inputs.

Includes a signed APK, checksums, certificate record, matched official OpenCode updates, source/build materials, Debian SPDX/license inventory, and the exact Debian source descriptors, upstream archives and distribution patches. Verify SHA256SUMS-signed.txt after downloading.

Engineering validation currently centers on Android 16 / ARM64 / 4 KiB Redmi K30 5G. The wider Android 10–16, ARM64 16 KiB, tablet/peripheral, real provider/GitHub, and long-term stress/security matrix remains incomplete. See docs/VALIDATION.md and THIRD_PARTY_NOTICES.md in the tagged source for evidence and audit boundaries. Host CI success does not establish those remaining claims.

Debian development tools are bundled; the graphical desktop is optional. Existing development APKs can have a different signing identity: preserve that installation and export projects before switching; cross-signer backup migration is not established.

Android ARM64 工程预发布：包含签名 APK、校验值、证书、匹配的 OpenCode 独立更新、构建记录、源码与 Debian 许可证/源码材料。当前真机证据主要来自 Android 16 / 4 KiB 的 K30；完整兼容性和压力验收仍在推进。安装前请核对签名，保留不同签名的旧测试安装及项目。
NOTES
files=(artifacts/opencode-android-full-arm64.apk artifacts/opencode-android-full-arm64.ocaupdate artifacts/opencode-android-full-arm64.ocafeed artifacts/SHA256SUMS-signed.txt artifacts/signing-certificate.txt artifacts/build-info.json artifacts/opencode-android-full-source.tar.gz artifacts/ci-native-sources.tar.gz artifacts/THIRD_PARTY_NOTICES.md artifacts/developer-packages-arm64.tsv artifacts/developer-deb-lock.json artifacts/gradle-runtime-dependencies.txt)
files+=(artifacts/debian-components.spdx.json artifacts/debian-inventory-coverage.json artifacts/debian-source-requests.json artifacts/debian-license-texts.zip)
files+=(artifacts/debian-source-archive-lock.json artifacts/debian-source-collection.json artifacts/debian-source-delivery.json artifacts/SHA256SUMS-debian-sources.txt artifacts/debian-source-part-*.tar.gz)
files+=(artifacts/bun-source.tar.gz artifacts/bun-tinycc-source.tar.gz artifacts/bun-webkit-source.tar.gz artifacts/opencode-source.tar.gz artifacts/upstream-source-delivery.json artifacts/upstream-license-texts.zip)
files+=(artifacts/maven-source-delivery.json artifacts/maven-sources.zip artifacts/maven-license-texts.zip artifacts/npm-notice-inventory.json artifacts/npm-components.spdx.json artifacts/npm-license-texts.zip)
if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --json isDraft >/dev/null 2>&1; then
    echo 'This tag already has a release; its assets were preserved. Use a new reviewed tag.' >&2
    exit 1
fi
gh release create "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --verify-tag --prerelease --title "$RELEASE_TAG" --notes-file artifacts/RELEASE_NOTES.md "${files[@]}"
