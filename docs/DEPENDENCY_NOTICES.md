# Dependency source and notice delivery / 依赖源码与许可证材料

The notice stage adds original attribution materials to the Android container's assets. It leaves the official OpenCode frontend/backend source and rendered interface intact.

该阶段为 Android 宿主加入原始版权材料，官方 OpenCode 前后端源码与界面保持原样。

## Android release artifacts

`exportReleaseArtifactInputs` resolves the actual release runtime classpath and hashes each artifact. Identical duplicate resolution rows are merged: the initial 69 rows represent 68 unique artifacts. The private input file contains Gradle cache paths and stays under `.cache/`; published records contain coordinates, sizes, hashes, and official source URLs.

导出任务解析实际 release 运行库并计算归档摘要；最初 69 条解析记录合并后为 68 个唯一归档。带 Gradle 缓存路径的输入只在 `.cache/` 内使用，公开记录不含宿主路径。

`third_party/maven-sources.lock.json` pairs each exact binary hash with its published POM and complete source JAR from Google Maven or Maven Central. `maven_materials.py` rejects a changed dependency graph/binary, verifies original source bytes, and collects license/NOTICE files and source attribution headers. The full source JARs are delivered in `maven-sources.zip`; the smaller notice archive is included inside the APK. Missing direct POM declarations remain visible rather than being guessed from group names.

固定锁将二进制摘要与官方 Maven POM、完整 source JAR 配对。脚本拒绝依赖图或二进制漂移，验证原始源码，保留 LICENSE/NOTICE 和源码版权头；完整源码单独分发，较小的说明 ZIP 内嵌 APK。POM 中缺失的直接许可证声明保留为未知，不从组织名称推断。

## OpenCode manifest dependency superset

The separate notice checkout uses the same official commit and Bun version, a frozen lockfile, Linux ARM64/glibc selection, and `--ignore-scripts`. No package lifecycle scripts run during metadata acquisition. The collector follows CLI/WebUI manifest dependencies and available optional/peer dependencies. This includes some build-related packages and is explicitly a superset, not a claim that every listed package is embedded in the executable.

独立说明材料检出使用相同官方提交和 Bun 版本、冻结锁、Linux ARM64/glibc 选择，并禁止包生命周期脚本。收集器沿 CLI/WebUI 的清单与可解析的可选/peer 依赖收集；包含部分构建相关项，因此明确标为超集，不宣称每项均嵌入可执行程序。

The local production-input run collected 685 metadata records. It preserves packaged license files, README grants, explicitly paired optional-package suite notices, and SHA-256-pinned repository supplements. Original font attribution/license name records are paired with the exact Inter and JetBrains Mono Nerd Font asset hashes, with the full SIL OFL text included. Fonts are read for metadata and remain unchanged.

本地 production 输入实测收集 685 条元数据，保留包内说明、README 授权、明确匹配的可选原生包套件说明及固定摘要的仓库补充。Inter 和 JetBrains Mono Nerd Font 的原始字体声明与实际资产摘要配对，并附完整 OFL；字体文件本身未改动。

The fixed r26b/r29 NDK notices are also retained in APK assets and checked by hash. These are a build-toolchain notice superset, including compiler/runtime attribution and notices for tools not distributed in the app. The r29 text was read from the original Linux NDK ZIP after verifying the already pinned complete ZIP SHA-256.

固定 r26b/r29 NDK 的原始说明也保留在 APK 内并核对摘要，作为构建工具链说明超集，包含编译支持/运行库署名以及部分未分发工具的条款；r29 文本来自已校验整体固定摘要的原始 Linux NDK ZIP。

Four packages in that run (`@npmcli/agent`, `@solidjs/meta`, `spdx-exceptions`, and `spdx-license-ids`) supplied a named standard license without a separately collected package/repository license file. Their published attribution metadata and full standard text are retained; `declarationOnlyReview` identifies these cases. Counts can differ when additional platform/peer packages are installed. They are not silently labeled as fully reviewed, and concluded licenses remain `NOASSERTION`.

其中四项只提供标准许可证声明，未取得独立的包/仓库许可证文件：脚本保存其公开署名与完整标准文本，在 `declarationOnlyReview` 中列出。安装更多平台或 peer 包时数量可能不同；不将这些项默认为审查完成，结论字段仍为 `NOASSERTION`。

## Packaging and verification

Run `bash tools/ci_prepare_notices.sh` after preparing runtime inputs and the build tools. It emits the public materials under `artifacts/` and places the two notice ZIPs in generated Android assets. CI checks the ZIP bounds/CRC, Maven pairing, and official OpenCode source revision inside the actual debug, unsigned release, and signed release APKs with `--require-license-materials`.

准备运行包和构建工具后执行该脚本，公开材料写入 `artifacts/`，两份说明 ZIP 加入生成的 Android 资产。CI 在实际 debug、unsigned release 和签名 APK 中检查 ZIP 边界/CRC、Maven 配对和 OpenCode 提交，缺少材料会失败。

SPDX/notice inventories record evidence and scope. They do not establish a completed legal compatibility/security/device audit. `fullReleaseAuditComplete` remains false, as does modified WebKit/Bun relink acceptance; the broader project validation record continues to apply.

清单记录证据和范围，不代表完整许可证兼容性、安全或设备审计；相关完成标记保持 false，修改 WebKit/Bun 后的完整重链接验收及整个项目验收仍单独推进。
