# Third-party components and provenance

| Component | Fixed source/version | Licence / use |
| --- | --- | --- |
| DroidNative Android build procedure and minimal talloc configuration | [DroidNativeApp fa591010](https://github.com/DroidNative/DroidNativeApp/tree/fa591010d6e33adbf5a79178b874fd4abdc58931) | GPL-3.0-or-later; `native-host/build.sh` adapted from its PRoot-only procedure; `native-host/talloc/config.h` and `replace_min.c` copied with attribution |
| DroidNative PRoot fork | [4ac94ecb](https://github.com/DroidNative/proot/tree/4ac94ecbb567608725e1d68d5ea617800a76c9c4) | GPL-2.0-or-later notices in upstream source; built as separate executable, no PRoot C linked into Android UI |
| talloc | [2.5.0](https://www.samba.org/ftp/talloc/) | LGPL-3.0-or-later; statically linked into PRoot; source/build instructions retained |
| SQLite | [3.54.1](https://www.sqlite.org/2026/sqlite-autoconf-3540100.tar.gz) | Public domain; statically linked into PRoot |
| OpenCode | [dev f993a89d](https://github.com/anomalyco/opencode/tree/f993a89d13c73ff6f6537d6b97e78e138a3ea363) | MIT; full server and official WebUI built from the same unmodified source commit |
| Embedded Bun runtime | [1.4.4 / 38afee6c](https://github.com/oven-sh/bun/tree/38afee6c84fcf48c0edb2466aa4b27d287a59c30) | Upstream license/third-party notice retained verbatim in APK assets; source archive and extracted notices included with release materials |
| Bun TinyCC | [05f0fafa](https://github.com/oven-sh/tinycc/tree/05f0fafaa3be31e31d7b4b5c17dc60f62c991171) | LGPL-2.1; source archive and Bun patch/build recipe retained |
| Bun JavaScriptCore / WebKit | [d6375c72](https://github.com/oven-sh/WebKit/tree/d6375c72affe0f70222cdcb45012b10f0bf3a9d5) | LGPL and other per-file terms; exact pin cross-checked against Bun's build script. Source/Tools/WebKitLibraries plus root files collected through upstream's documented public Git path and pinned by uncompressed tar SHA-256; unrelated top-level test/site trees omitted. Modified-runtime relink validation remains open |
| Debian 13 ARM64 / diagnostic amd64 | `runtime/debian-*.json` | Official debuerreotype Docker Debian artifacts; individual package copyrights remain in `/usr/share/doc/*/copyright` |
| Gradle wrapper | 8.11.1 | Apache-2.0; official Gradle source; distribution SHA-256 pinned |
| AndroidX / Compose | See `app/build.gradle.kts` | Apache-2.0; published Maven dependencies |
| Kotlin / kotlinx.coroutines | 2.2.10 / 1.10.2 | Apache-2.0 |
| JUnit (instrumentation/controller only) | 4.13.2 | EPL-1.0 per the published POM; included only in test APKs, not the product release APK; separate test-distribution review remains part of the release audit |
| xterm.js / fit addon | [5.5.0 / 0.10.0](https://github.com/xtermjs/xterm.js) | MIT; npm SHA-512 SRI pinned in `runtime/terminal.lock.json`; licence files included with APK assets |
| Apache Commons Compress | 1.27.1 | Apache-2.0; archive decoding |
| Apache Commons IO / Lang | 2.16.1 / 3.16.0 | Apache-2.0; Commons Compress runtime dependencies |
| Google Tink Android | 1.24.0 | Apache-2.0; authenticated streaming workstation backups |
| Gson / Error Prone annotations | 2.13.2 / 2.41.0 | Apache-2.0; resolved Tink runtime dependencies |
| noVNC / websockify | Debian trixie packages; observed 1.6.0 / 0.12.0 | MPL-2.0 / LGPL-3.0; installed on demand from signed Debian repositories; gateway uses the upstream public Python API and noVNC RFB module |
| XFCE / TigerVNC / Chromium | Debian trixie/security ARM64 packages | Per-package licences in `/usr/share/doc/<package>/copyright`; installed on demand, with package inventory in desktop-state |

The application is an unofficial port and is not endorsed by the upstream projects.
The native Android launcher artwork uses the official OpenCode brand mark as a reference;
generation provenance and the final prompt are recorded in `docs/APP_ICON.md`.
Native language packs are bundled text resources, with machine-translation provenance and
review limits recorded in `docs/NATIVE_I18N.md`; no translation SDK is included in the app.
The Debian-only inventory generator and its remaining source-delivery limitations are
documented in `docs/DEBIAN_INVENTORY.md`.
`runtime/upstream-source-lock.json` pins the separately delivered OpenCode, Bun and
TinyCC archives by SHA-256/size. `tools/upstream_sources.py` preserves original
bytes and extracts license/notice texts without unpacking or executing sources.
External source references are not counted as collected archives. Transitive
npm/Maven notice completeness, modified WebKit/Bun relink validation and full compatibility
review are tracked in `docs/DEPENDENCY_NOTICES.md`; `fullReleaseAuditComplete` remains false.
The APK contains Maven/npm notice archives. The actual 68 unique release artifacts
are paired by binary SHA-256 with complete published source JARs and POMs in
`third_party/maven-sources.lock.json`. A manifest dependency superset, original
font metadata/OFL text, packaged grants and pinned repository supplements are also
retained. Declaration-only items remain explicit review cases, not inferred approvals.
Source/notice delivery and the broader compatibility review are distinct; this
development inventory is not a completed release audit.
