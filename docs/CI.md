# 自动构建与发布

`.github/workflows/android.yml` 响应 push、pull request 和手动运行。公开仓库为 [B5-Software/opencode-android-full](https://github.com/B5-Software/opencode-android-full)，托管状态以 [Actions](https://github.com/B5-Software/opencode-android-full/actions) 的实际记录为准。文末保留先前本地演练的历史记录；这些记录不代表托管运行已通过。

## 工作流

| 阶段 | 产物与检查 |
| --- | --- |
| ARM64 runtime | 固定 Debian OCI 层、签名快照 apt 索引及 551 个 deb、完整开发套件、未修改的官方 CLI/WebUI、16 KiB PRoot/Loader、SRI 固定的终端库 |
| Android | debug APK、测试 APK、未签名 release APK；单元测试、lint、LSP 协议测试、版本元数据测试及 APK/ELF/嵌入包摘要检查 |
| vMAJOR.MINOR.PATCH 标签 | 使用 release 环境的密钥签名 APK、独立 OpenCode 更新包及 HTTPS 更新源 `.ocafeed`，验证签名，生成 SHA-256 并公开工程预发布 |

运行包缓存只包含可再生的发行输入。缓存键包括实际运行时锁文件、制作脚本和固定工具链标识；源码审计清单、审计签名策略和单纯工作流编辑不触发运行时重建。制作步骤或工具链变更需同步更新脚本或缓存版本。命中后仍检查 rootfs、官方嵌入包、开发依赖图、原生 ELF 和终端文件摘要，不使用宽泛旧键回退。上游源码、手机数据、模型凭据和签名密钥都不作为运行包缓存输入。

NDK r29 `29.0.14206865` 用于 PRoot/Loader；JNI 继续使用项目已验证的 `26.1.10909125` 和显式 16 KiB 链接参数。构建宿主为 Ubuntu 24.04，JDK 21，Gradle 8.11.1。Bun 版本由 `runtime/opencode-dev.lock.json` 读取。Actions 引用已固定到完整提交 SHA。

冷构建使用 Linux QEMU/binfmt chroot 制作干净 Debian 开发镜像，不从手机导出环境，也不依赖 Docker。第一次运行需下载工具链与依赖；常规 Android 界面或业务代码变化可复用已校验的运行包。

## 签名配置

在 GitHub 仓库的 `release` Environment 中配置：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 keystore 文件的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 正式签名 alias |
| `ANDROID_KEY_PASSWORD` | alias 的密钥密码 |

密钥只在标签的签名步骤中写入临时 0600 文件，并在退出时移除。产物只包含公开签名证书信息。按需为该 Environment 配置发布审核人；普通分支、PR 和手动构建不进入签名任务。

持续升级使用同一个正式签名身份。CI debug APK 使用 Runner 的开发签名，适合工程验证；正式保留数据升级应使用持续维护的正式 keystore。keystore 和密码保留在本地安全存储/GitHub Secrets，源码及聊天中不存放这些值。

标签版本分配 Android versionCode：`major × 10000000 + minor × 10000 + patch × 10 + channel`，正式版 channel=9，预发布=0；major≤209、minor/patch≤999。这样同一版本的正式版高于预发布版，下一 patch 更高。开发构建使用 `0.1.0-dev.<commit>`、code=10000。明确版本输入记录于 `build-info.json`，本地重建可传 `-PappVersionName=... -PappVersionCode=...`；PowerShell 中需将每个完整 `-P...` 参数加引号。

## 产物

Actions 的 Android artifact 包含 APK、完整构建输入记录、SHA256SUMS、项目源码包、固定 PRoot/talloc/SQLite 源码与本地补丁、Debian 包清单、包归档锁和第三方说明。检查报告另存为独立 artifact。运行包中间产物保留 7 天，Android 产物/报告保留 14 天。

标签步骤生成签名 APK 和 `.ocaupdate`，随后公开工程预发布。相同标签已有 Release 时脚本保留其资产并失败退出，避免重跑覆盖已分发内容。发布记录保留真实设备验收和依赖审计边界；当前工程记录不代表完整 M0–M5 验收或安全/许可证审计已结束。

Debian 源码阶段对实际 unsigned APK 的 629 个二进制包重新映射源码，使用 APK 中的固定归档公钥校验三份固定 InRelease，再校验 Sources 索引和精确源包版本。完整源码图必须匹配 `runtime/debian-source-archive-lock.json`，包括请求清单摘要、源文件大小与 SHA-256。每次构建的 rootfs 摘要保留在生成清单中；不假定含包管理器生成数据的 rootfs 跨宿主字节一致。归档收集成功后按约 512 MiB 分卷，和描述符、上游源码、Debian 补丁、验证脚本与签名索引一起作为发布资产。失败会阻止上传该轮 APK 产物及标签发布。

`.ocaupdate` 的格式与当前边界见 [独立更新包](OPENCODE_UPDATES.md)。已接入原生确认、状态快照、切换、真实启动检查及失败恢复；线上下载和不同 Host/schema 的完整矩阵尚未完成。签名步骤先用相同 keystore/alias 签署内置完整程序证明，注入未签名 APK 后再 zipalign/sign；JDK 21 的 `VerifyCoreProof.java` 通过 apksig 验证实际 APK 证书及证明签名，静态脚本检查 APK 内完整运行包的摘要、大小、版本和提交对应。拒绝将证明追加到已签名 APK。

## 本地验证与限制

2026-10-28 已完成：

- 新 Python 入口实际构建固定提交的官方 ARM64 前后端，ELF 16 KiB 检查通过，保留现有 APK 资产。
- 551 个锁定 deb 的离线摘要验证，现有运行包完整校验通过。
- 未签名 release APK 构建、单元测试和 lintRelease 通过，APK 的 SDK/ABI/嵌入包摘要及原生对齐通过。
- actionlint 1.7.7 语义检查、Linux Bash 语法检查通过；3 项版本/CI 输出集成测试通过。
- 使用独立测试 keystore 完成实际 APK 签名与 apksigner 验证、更新 manifest 签名自检、更新 ZIP 项目/负载摘要验证。没有发布或安装这份测试签名 APK。

2026-10-29 追加本地演练：实际未签名 release APK → 注入内置程序证明 → 16 KiB zipalign → 本机 SDK debug 身份签名 → apksig/同 signer 的证明验证 → APK 内嵌包摘要检查；同时确认无证明的旧签名 APK 也不能作为注入输入。证据 `.cache/evidence/core-proof-release-signing-smoke.txt`。这份演练 APK 未安装/发布，也不代表 GitHub 正式环境或正式签名已配置。

主机 CI 不运行 ARM64 Android 真机测试，绿色工作流也不替代 Android 10–16、ARM64 4 KiB/16 KiB、外设和长时间压力验收。根目录 README 与 [验收记录](VALIDATION.md) 保留完整未完成项。字节级跨机器可复现性尚未独立证明。

2026-10-29 在线源追加验证：Python 生产器测试 2 项、已有 CI 元数据测试 3 项、actionlint 1.7.7 与 Bash 签名/草稿脚本语法检查通过；实际 SDK debug 签名的 `.ocafeed` 已在 K30 上由当前 APK 公钥验证，并与内置完整程序证明匹配。未创建远程 Release 或执行 GitHub Runner。格式和配置入口见 [OpenCode 更新](OPENCODE_UPDATES.md)。

参考：[GitHub Actions 产物](https://github.com/actions/upload-artifact)、[Android NDK](https://developer.android.com/ndk/downloads)。
