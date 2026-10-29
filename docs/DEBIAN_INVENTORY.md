# 从实际 APK 生成 Debian 清单

`python tools/debian_sbom.py --apk <APK> --output <新目录>` 直接读取实际 APK 内的 Debian 归档，校验 pack 元数据和根文件系统摘要，并核对 dpkg 已安装状态与内置开发套件清单。它不会把归档解压到宿主文件系统。

输出包括 SPDX 2.3 JSON、覆盖报告、对应源包请求清单和许可证文本 ZIP。版权文件的普通文件、相对／绝对目录链接及硬链接在归档内部解析，拒绝越界、循环、重复或无效成员，并限制读取数量与大小。许可证文本保留原始字节；不能从包名推断许可证，无法确认的字段保留 `NOASSERTION`。

当前 ARM64 实际 APK 的 Debian 部分包含 629 个已安装二进制包；629 个包均找到版权文件，归档收集 655 份版权／通用许可证文本，归并为 485 个 Debian 源包身份。当前清单没有附带这 485 个源包归档，报告明确记录 `sourceArchivesCollected = 0` 和 `fullReleaseAuditComplete = false`。

实际生成的 SPDX 文档通过了 [SPDX 2.3 官方 JSON Schema](https://raw.githubusercontent.com/spdx/spdx-spec/v2.3/schemas/spdx-schema.json) 校验；验证记录在 `.cache/evidence/debian-spdx-schema-validation.json`。CI 为实际 unsigned APK 生成清单，签名助手也为实际 signed APK 重新生成，校验文件包含这四个输出，草稿发布助手可附带它们。

这是 Debian 部分的可核对清单。完整发行前仍需完成 Maven/Bun/其他传递依赖清单、适用许可证和对应源码交付，不代表整个 APK 的发布审计已经完成。


## 精确源码收集与分发

`tools/debian_sources.py` 已对 485 个源码身份生成固定源码图，共 2209 个原始文件、1,297,114,021 字节。`plan` 从 APK 提取并校验归档公钥，核对固定 InRelease 的 SHA-256 和明确允许的 Debian 13 主签名指纹，再由认证的 Sources 索引匹配精确名称/版本。包含 epoch 的版本保持原值；上游源码、`.dsc` 与 Debian 补丁全部保留原始字节。下载使用内容寻址文件名和大小/SHA-256 校验，既不解压到宿主路径，也不执行源码。`.dsc` 的名称、版本和文件摘要清单还会与认证索引交叉检查。

单独的 `debian_sbom.py` 仍报告没有收集源码；源码步骤另产出 `debian-source-collection.json`，只有对象和描述符全部验证后才记录收集成功。不能把“生成请求清单”当作“源码已交付”。全部对象齐备后 `package` 按约 512 MiB 分卷，附实际 rootfs 绑定、认证索引、公钥、原始源码和离线验证/恢复文件名工具。主机单元测试涵盖真实 GPG 签名、错 signer/被改签名、目录越界、对象/锁图关联、损坏下载与缓存、描述符、分卷确定性和原文件名恢复。

```bash
# 在已产生实际 APK 的 Debian inventory、并准备锁定镜像索引后：
python3 tools/debian_sources.py keyring --apk app.apk --output .cache/source-keyring.pgp
python3 tools/debian_sources.py plan --inventory inventory --keyring .cache/source-keyring.pgp --expected-lock runtime/debian-source-archive-lock.json --output source-plan.json
python3 tools/debian_sources.py collect --lock source-plan.json --objects .cache/debian-source-objects --report source-collection.json
python3 tools/debian_sources.py package --lock source-plan.json --objects .cache/debian-source-objects --inventory inventory --keyring .cache/source-keyring.pgp --output source-delivery
```

把所有分卷解压到同一新目录后，可运行其中的 `python tools/debian_sources.py verify --lock SOURCE_MANIFEST.json --objects objects` 离线校验，再使用 `materialize --name <源包名> --output <新目录>` 恢复 Debian 原文件名，由用户选择是否运行 `dpkg-source -x`。源码分发不是整个 APK 的许可证兼容性结论；Bun/WebKit、npm、Maven 和其他构件仍有单独审计边界，见第三方说明。


2026-10-30：实际 K30 工程 APK（SHA-256 `3f6973721003046e9527836c01539804ab489c5d638466b8873aadc48a08fac7`）对应 rootfs 的 485/485 个源包、2209/2209 个源文件已全部校验，原始文件总计 1,297,114,021 字节；无失败或不完整项。实际分发试包形成三个分卷，大小分别为 512,605,627、504,218,337、289,641,168 字节。证据为本地 `artifacts/debian-source-collection.json` 和 `artifacts/debian-source-delivery/`，不把本地试包等同于已完成线上 Release。根目录 SDK/上游组件审计仍有独立未完成项。

同一快照 URI 的 trixie-updates 在托管 CI 返回另一份 Debian 有效签名信封：日期从 2026-10-26 14:08:17 变为 20:08:23，但锁定 binary-arm64 Packages.xz 摘要保持不变。现将此前已核验的三份 InRelease 原始字节保存在 `runtime/apt-metadata/`，用 Git `-text` 保持字节，仍按原 SHA-256、公钥与索引摘要验证；不自动接受重新签署的信封或重解析依赖图。
