# Bundled Bun source materials / 内嵌 Bun 源码

The official OpenCode executable embeds Bun. Its source delivery is separate from the Debian dpkg inventory. `runtime/upstream-source-lock.json` identifies the actual pinned OpenCode commit, Bun 1.4.4, and Bun's TinyCC/WebKit dependency commits.

官方 OpenCode 可执行程序内嵌 Bun，不能把它计入 Debian 的 dpkg 包清单后便视为已完成源码交付。上游源码锁记录 OpenCode 固定提交、Bun 1.4.4 及其实际 TinyCC/WebKit 依赖提交。收集器会读取已取得 Bun 源码中的版本与构建脚本，交叉核对这些版本。

`tools/upstream_sources.py` retains the original SHA-256/size-checked OpenCode, Bun, and TinyCC archives. Bun's pinned WebKit source is acquired through the public Git path documented by [Bun upstream](https://bun.com/docs/project/license), because its archive endpoint returned HTTP 422. The WebKit collection includes `Source`, `Tools`, `WebKitLibraries`, and root files; unrelated top-level tests/site trees are omitted. Its canonical uncompressed Git tar is pinned by SHA-256 and size, allowing gzip compression to differ between hosts without changing accepted source bytes. No acquired source code is executed by the collector.

OpenCode、Bun、TinyCC 的原始归档按大小与 SHA-256 保留。WebKit 的归档接口返回 HTTP 422 后，按 Bun 官方说明使用公开 Git 来源取得固定提交，仅归档 `Source`、`Tools`、`WebKitLibraries` 与根目录文件，略去无关的顶层测试和网站树。锁定的是原始 Git tar 的大小和 SHA-256；不同宿主的 gzip 压缩可不同，但接受的源码字节必须相同。收集器不执行取得的源码。

The local engineering collection contains four archives and 177 verbatim license/notice texts. The WebKit build-source tar is 1,426,841,600 bytes before compression; the local gzip is 425,183,015 bytes. Release delivery metadata records each actual distributed archive hash and size. This is not a completed transitive npm/Maven license compatibility review.

本地工程试包已形成四个上游源码归档、177 份原始许可证/版权说明。WebKit 构建源码 tar 为 1,426,841,600 字节，本地 gzip 为 425,183,015 字节。线上产物会记录其实际归档摘要；这些材料不代表 npm/Maven 的传递许可证兼容性审查已结束。

## Modified runtime relinking / 修改后的运行时重链接

Bun's upstream instructions describe placing the pinned WebKit checkout in `vendor/WebKit`, synchronizing the source pin, and running `bun run build:local`. The delivered Bun archive includes its build scripts and TinyCC patches. Its ordinary external build dependencies are described in that source.

上游说明通过 `vendor/WebKit`、`bun sync-webkit-source`、`bun run build:local` 构建修改后的 Bun；交付的 Bun 源码保留了构建脚本与 TinyCC 补丁。其他构建依赖以该固定版本源码为准。

OpenCode's `packages/cli/script/build.ts` passes `compile.executablePath` to `Bun.build`. A developer relinking a private variant can use a separate source copy and select their rebuilt ARM64 Bun executable through that option, then build the matched official frontend/backend. `tools/bundle_opencode.py arm64-v8a <binary>` validates the resulting ELF and packages it for a locally rebuilt APK. Use your own APK signing identity and matching publisher proof; signed release verification is not disabled.

OpenCode 构建脚本使用 `compile.executablePath` 选择编译模板。开发者可在单独的源码副本中把该选项指向自己重编译的 ARM64 Bun，再构建匹配的官方前后端；`tools/bundle_opencode.py arm64-v8a <binary>` 校验并打包 ELF，随后以自己的 APK 签名与程序证明构建宿主，不关闭发布签名验证。

**Validation boundary:** source identity, hashes, raw notice collection, and delivery have been exercised. A complete modified WebKit → Bun → OpenCode → Android relink has not yet been executed. It remains an explicit acceptance item; `modifiedRuntimeRelinkVerified` and `fullReleaseAuditComplete` remain false.

**验证边界：**已实际核对源码身份、摘要并收集/分发原始许可证；修改 WebKit 后重新构建 Bun、OpenCode 和 Android 的完整链路尚未实测，相关完成标记保持 false。
