# Bundled Bun source materials / 内嵌 Bun 源码

The official OpenCode executable embeds Bun. Its source delivery is separate from the Debian dpkg inventory. `runtime/upstream-source-lock.json` identifies the actual pinned OpenCode commit, Bun 1.4.4, and Bun's TinyCC/WebKit dependency commits.

官方 OpenCode 可执行程序内嵌 Bun，不能把它计入 Debian 的 dpkg 包清单后便视为已完成源码交付。上游源码锁记录 OpenCode 固定提交、Bun 1.4.4 及其实际 TinyCC/WebKit 依赖提交。收集器会读取已取得 Bun 源码中的版本与构建脚本，交叉核对这些版本。

`tools/upstream_sources.py` retains the original SHA-256/size-checked OpenCode, Bun, TinyCC and ICU archives. Bun's pinned WebKit source is acquired through the public Git path documented by [Bun upstream](https://bun.com/docs/project/license), because its archive endpoint returned HTTP 422. The current WebKit collection includes `Source`, `Tools`, `WebKitLibraries`, `.github`, `Configurations`, `icu`, `macos-cross`, `metadata`, `resources`, `scripts`, `WebKit.xcworkspace` and root files; unrelated top-level tests/site trees are omitted. Its canonical uncompressed Git tar is pinned by SHA-256 and size, allowing gzip compression to differ between hosts without changing accepted source bytes. No acquired source code is executed by the collector.

OpenCode、Bun、TinyCC 与 ICU 的原始归档按大小与 SHA-256 保留。WebKit 的归档接口返回 HTTP 422 后，按 Bun 官方说明使用公开 Git 来源取得固定提交；当前集合包含核心源码、构建脚本、ICU 描述、配置与支持资源，具体目录见上文，略去无关的顶层测试和网站树。锁定的是原始 Git tar 的大小和 SHA-256；不同宿主的 gzip 压缩可不同，但接受的源码字节必须相同。收集器不执行取得的源码。

The first engineering release's collection contained four archives and 177 verbatim license/notice texts. Its WebKit tar was 1,403,084,800 bytes before compression. Subsequent rebuild preparation identified omitted build/ICU recipes. Current main expands those inputs and adds ICU 78.3's original source archive, whose SHA-256 is read from the acquired WebKit `icu/source.json` and checked against the release input. The new canonical WebKit tar is 1,405,962,240 bytes, SHA-256 `0b1e16e1f31dff5224501cc52093e676cbffd3a55324b7bceef98b039b498819`. Earlier published assets remain historical records. This is not a completed transitive license compatibility review.

首个工程预发布收集了四个上游源码归档、177 份原始许可证/版权说明，其 WebKit tar 为 1,403,084,800 字节。后续重建准备发现构建/ICU 配方遗漏；当前 main 补齐这些输入，并增加 ICU 78.3 原始源码，将实际 WebKit `icu/source.json` 的摘要与分发输入交叉核对。新 tar 大小及摘要见上文，既有发布资产保留为历史记录。这不代表完整传递许可证兼容性审查已结束。

2026-10-30: the first Windows export inherited system `core.autocrlf=true`, while CI ignored system Git configuration. Git archive applied different line-ending conversions. Acquisition/export now explicitly disables automatic conversion, fixes LF defaults and tar permissions, and ignores host attributes/configuration. Upstream committed `.gitattributes` still applies. The source commit and selected paths are unchanged. Regression checks compare exports under conflicting host settings, preserve original LF source text, and retain exact tar-size/SHA-256 verification.

2026-10-30：首次 Windows 导出继承了系统的 `core.autocrlf=true`，CI 则不读取系统 Git 配置，Git 归档因此进行了不同的换行转换。现对获取和导出明确固定自动转换、默认 LF、tar 权限及宿主属性/配置边界；保留上游提交的 `.gitattributes`。源码提交和选择路径未变。回归测试覆盖相冲突的宿主设置、原始 LF 文本和严格的 tar 大小/摘要校验。

## Modified runtime relinking / 修改后的运行时重链接

The [published beta.1 build-source supplement](https://github.com/B5-Software/opencode-android-full/releases/download/v0.1.0-beta.1/upstream-build-sources-v0.1.0-beta.1.zip) now delivers the expanded five-archive collection and 179 original notices in one file. It is 629,303,584 bytes, SHA-256 `269cb1cd5981499da11aef9f7483a2ff2b904238c5e5ff8f32807a8b04988b70`. Extract into a new directory and run `python VERIFY.py`. Fresh extraction checks the archive hashes/lengths, canonical WebKit tar, actual Bun dependency pins and WebKit ICU descriptor without executing acquired source. The uploaded file was downloaded again and matched; all 35 original release assets retain their IDs, lengths and digests.

[beta.1 构建源码补充包](https://github.com/B5-Software/opencode-android-full/releases/download/v0.1.0-beta.1/upstream-build-sources-v0.1.0-beta.1.zip)现已把补齐的五份归档和 179 份原始说明作为一个文件交付，大小与 SHA-256 见上文。解压到新目录后运行 `python VERIFY.py`；新目录校验及发布后实际下载的摘要验证已通过。原 35 个附件的身份、长度和摘要均保留。该补充只修正上游构建源码交付，不构成完整发行审查或重链接验收。

2026-10-30 initial preparation (before the completed proof below): the delivered Bun/TinyCC/WebKit sources were expanded in a separate WSL directory, with no omitted fixture links. LLVM 23.1.1, Rust `nightly-2026-09-15`, CMake 3.30.5 and the pinned ARM64 glibc/GCC sysroot were prepared in task-owned caches. A C++20 AArch64 executable compiled and ran under build-host QEMU; Bun's `release-local`, `linux-aarch64-gnu` configuration then succeeded with local WebKit (23 dependencies, 228 codegen steps, 1195 objects). This establishes build preparation, not completed compilation or Android execution. The first preparation attempt exposed the missing build/ICU source materials described above; the expanded collector now verifies five archives and 179 original notice files.

2026-10-30 初期准备记录（完整实测结果见下文）：在独立 WSL 目录从已交付归档展开 Bun/TinyCC/WebKit，没有跳过夹具链接；独立缓存中准备了固定 LLVM、Rust、CMake 与 ARM64 glibc/GCC 编译根目录。C++20 AArch64 程序在构建主机 QEMU 运行通过，Bun 的本地 WebKit/ARM64 GNU 配置成功。该结果仅证明构建准备，不代表完整编译或 Android 执行。准备过程发现了上述构建/ICU 材料缺口，补齐后收集器实际验证五份归档、179 份原始说明。

Bun's upstream instructions describe placing the pinned WebKit checkout in `vendor/WebKit`, synchronizing the source pin, and running `bun run build:local`. The delivered Bun archive includes its build scripts and TinyCC patches. Its ordinary external build dependencies are described in that source.

Further build evidence: ICU 78.3 host tools and ARM64 static libraries compiled from the delivered source. The target data applies the shipped decompression-hook patch, removes 315 unused items, retains 3,990 items and the reachable `ja`/`zh`/`zh_Hant` numbering resources, and is repacked with Node 26.3.0 and Zstd 1.5.7 after checking their published/source-pinned hashes. The compressed data archive is 12,249,374 bytes and its object is AArch64. A separate validation copy adds a fixed VM diagnostic marker and adjusts GNU cross-build/ICU parameters that the upstream local-mode flags otherwise overwrite. The full source build subsequently completed, and the modified runtime passed AArch64 QEMU calendars and Android execution. See the [rebuild patches, procedure and exact proof scope](relink/README.md).

后续构建证据：已从交付源码编译 ICU 78.3 宿主工具和 ARM64 静态库，应用上游解压 hook、过滤及压缩配方，保留算法编号所需资源；数据归档的目标对象为 AArch64。独立验证副本加入固定 VM 标记，并调整上游本地模式覆盖 GNU 交叉编译参数及 ICU 选择的问题。随后完整源码编译完成，修改后的运行库通过 AArch64 QEMU 日历测试及 Android 执行。[构建补丁、步骤与实测范围](relink/README.md)已记录。

上游说明通过 `vendor/WebKit`、`bun sync-webkit-source`、`bun run build:local` 构建修改后的 Bun；交付的 Bun 源码保留了构建脚本与 TinyCC 补丁。其他构建依赖以该固定版本源码为准。

OpenCode's `packages/cli/script/build.ts` passes `compile.executablePath` to `Bun.build`. A developer relinking a private variant can use a separate source copy and select their rebuilt ARM64 Bun executable through that option, then build the matched official frontend/backend. `tools/bundle_opencode.py arm64-v8a <binary>` validates the resulting ELF and packages it for a locally rebuilt APK. Use your own APK signing identity and matching publisher proof; signed release verification is not disabled.

OpenCode 构建脚本使用 `compile.executablePath` 选择编译模板。开发者可在单独的源码副本中把该选项指向自己重编译的 ARM64 Bun，再构建匹配的官方前后端；`tools/bundle_opencode.py arm64-v8a <binary>` 校验并打包 ELF，随后以自己的 APK 签名与程序证明构建宿主，不关闭发布签名验证。

**Validation boundary:** the pinned modified WebKit → rebuilt Bun → official OpenCode → installed APK read-only loader chain passed on K30 / Android 16 / ordinary UID / 4 KiB. Authenticated API, frontend delivery, session/SQLite creation and a real GLM Agent C build passed with the same rebuilt executable. The official frontend archive is byte-identical to the original. `modifiedRuntimeRelinkVerified` is now true for the [recorded pins and scope](relink/validation.json); `fullReleaseAuditComplete` remains false. Published APK replacement, 16 KiB physical devices, the full device/account/lifecycle matrix and complete license review are not established by this result. The frozen beta.1 supplement preserves its earlier collection-time flags.

**验证边界：**固定源码的修改 WebKit → 新 Bun → 官方 OpenCode → 现有 APK 只读 Loader 链路已在 K30 / Android 16 / 普通 UID / 4 KiB 通过，包含鉴权接口、前端交付、会话/SQLite 与同一重编译程序的真实 GLM Agent C 工程。前端资源与原版逐字节一致。`modifiedRuntimeRelinkVerified` 仅对[本次固定输入与范围](relink/validation.json)为 true，完整发行审计仍为 false；这不证明分发 APK 的替换安装、16 KiB 真机或全部设备/账号/生命周期和许可证矩阵。冻结的 beta.1 补充包保留交付时的历史标记。
