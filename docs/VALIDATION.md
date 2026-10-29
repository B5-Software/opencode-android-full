# 验收记录

记录更新至：2026-10-29。验收结论只适用于对应架构、API 和页面大小。

## 2026-10-29：默认隐私配置与本地认证直连

[默认隐私配置说明](PRIVACY_DEFAULTS.md)记录当前 pin 的 Sentry/OTLP 初始化条件、构建环境清理和原生本地认证边界。构建脚本移除继承的 Sentry 上传与 OTLP 配置、保持空前端 DSN，新增 3 个隐私测试加入原 CI 入口，与既有元数据测试共 6 项通过。主机单元测试 13 项、debug/release 构建、两种 lint 和测试 APK 构建通过。

K30 5 项、86.121 秒通过：4 项回环代理测试先证明临时 ProxySelector 可以将无秘密对照请求送入陷阱，再验证 Basic/匿名认证、一次性配对、浏览器 Cookie、重定向与非回环地址拒绝；这些认证请求不再选择该代理。另 1 项真实 Debian/RuntimeService 验收向 PRoot 宿主注入合成 OTLP/Sentry/提供商变量，验证来宾未继承且 `DO_NOT_TRACK=1`，并继续通过 gcc、9 个捕获进程退出、双 PTY 中断、自动重启拒绝、官方会话和项目保留及显式恢复。记录 `k30-loopback-privacy.txt`，没有采集实际用户环境值。

锁定 Bun 下，上游显式配置本地 OTLP collector 的原测试 1 项通过；默认无 endpoint/空 endpoint 的远程日志列表为 0，空 tracing layer 可运行 span。源路径和哈希见 `privacy-source-audit.json`，补充证据 `privacy-build-env-tests.txt`、`upstream-otel-control-test.txt`、`upstream-otel-default-check.txt`。这些属于当前默认配置的限定审阅及测试，不构成所有软件/插件/未来版本的完整零外连证明。

APK 实际签名与内置程序证明、ARM64/API 29–36/16 KiB 静态检查通过；release 不含 debug 测试服务。冻结 `artifacts/opencode-android-full-arm64-privacy-debug.apk`，629558884 字节，SHA-256 `75bcb08bf1ca89439e9293604cfa44e91c50d98645956f9aa457da0bc6ce5100`；配套测试包及 `SHA256SUMS-privacy-arm64.txt` 同目录。此版已安装 K30，并请求恢复正常 profile；当前仍 `deviceLocked=1`，本轮没有可见网页帧率/布局验收，也没有正式签名发布结论。官方前端和完整运行包未修改，完整 M0–M5 继续进行。

## 2026-10-29：重复退出与端口释放时序

[实际后端重复启停验收](RUNTIME_RESTART_TESTS.md)最终 1 项、136.681 秒通过，含新 APK Debian、同一真实官方会话、15 次 gcc/PTY 操作和主动停止/PRoot 强杀/Android 宿主强杀各 5 轮。各轮在进程退出后实际重新预留端口、重启官方服务、验证项目与严格累计执行次数，最终显式恢复成功。主动停止资源释放 37–80 ms、PRoot 强杀 64–522 ms、宿主强杀 65–100 ms。

已复现前轮即刻 bind 失败的时序条件：最终宿主强杀 5 轮中 4 轮在捕获 PID 均不再运行后仍有短暂监听，进程状态与端口可预留间最大 57 ms；首轮诊断 15 轮还观察到最大 60 ms。由此将原宿主退出测试改为同一 15 秒期限内同时验证进程退出及端口真实可预留；修改后另 1 项、85.542 秒通过，含自动重启拒绝、双 PTY 中断、会话/项目保存及显式恢复，不降低认证或进程断言。该轮进程退出 58 ms、端口释放 114 ms、重试 2 次。具体内核资源归因仍未证实，没有据此修改生产端口或启动逻辑。

构建/测试 APK/debug lint 通过；配套 QA 主 APK 的实际签名/内置程序证明、ARM64/API 29–36/16 KiB 静态检查通过。证据 `.cache/evidence/k30-runtime-restart.txt`、`k30-runtime-restart-summary.json`、`k30-runtime-release-deadline.txt`、`runtime-restart-apk-static.txt`、`runtime-restart-apk-proof.txt`。

QA 制品 `artifacts/opencode-android-full-arm64-restart-qa-debug.apk` 为 629558884 字节，SHA-256 `5427dde0b536ec0ae1d7100ae8434786a7f4ddd9e96078d69e0b93b6b12f9286`；配套测试包与校验文件 `SHA256SUMS-restart-qa-arm64.txt` 同目录。K30 已恢复 native-log 日常调试版并请求正常 profile，锁屏状态仍为 true。本轮不增加可见网页帧率、LMK/电池、其他 API 或 16 KiB 实机验收结论，完整 M0–M5 继续进行。

## 2026-10-29：原生日志与渲染状态隔离

[原生诊断日志](NATIVE_DIAGNOSTICS.md)将运行状态与日志正文分开，工作区、桌面和维护控件订阅不含正文的状态，正文仅在日志组件中收集。Host 输出按 250 ms 合并通知，缓冲保留完整行；分块 UTF-8 读取、长行整行略去和凭据脱敏覆盖 Runtime/桌面输出及异常处理。官方 OpenCode 前端与用户交互 PTY 未修改。

主机单元测试 13 项通过，其中新增日志测试 8 项，覆盖 10,000 行突发、8 并发来源、凭据租约/格式、ANSI/私钥、2 MiB 无换行输入恢复、单字节 Unicode 和最长普通字段扫描。debug/release 构建、测试 APK 及两种 lint 通过；APK/内置官方程序同 signer 证明、ARM64/API 29–36/16 KiB 静态检查通过，release 不含 debug 测试服务。证据为 `.cache/evidence/diagnostic-log-final-build.txt`、`diagnostic-log-test-final-build.txt`、`diagnostic-log-apk-static.txt`、`diagnostic-log-apk-proof.txt`、`diagnostic-log-release-components.txt`。

K30 最终 11 项、88.725 秒通过：日志/状态 2 项、运行历史 8 项、真实 RuntimeService 主进程终止恢复 1 项。2,000 行 Runtime 突发用时 319 ms、正文发布 1 次，Runtime 和桌面各 2,000 行均产生 0 次日志所致的工作区状态通知；阶段变化立即可见。真实独立 Debian 上仍验证 gcc、9 个已捕获进程退出、认证官方会话保留、2 个中断 PTY、自动重启拦截及显式重启不重放命令。证据 `.cache/evidence/k30-diagnostic-log-delivery.txt`。

本轮首个组合中 10/11 项通过，真实进程退出后的即刻端口预留返回 EADDRINUSE，原记录保留为 `k30-diagnostic-log-delivery-first.txt`。测试增加最长 15 秒的资源释放等待，仍要求实际预留端口成功，遇占用则检查存活监听；最终重跑预留用时 1 ms、重试 0 次，未复现占用，原先占用来源尚未定位，后续稳定性压力验证继续跟踪。

设备仍报告 `deviceLocked=true`，本轮未验证可见 WebView 帧率、正常浏览器 Cookie API 或新容器的可见布局。日志通知计数不代表网页 FPS。已请求恢复正常 profile 的 MainActivity，保留用户数据和浏览器资料。

冻结制品 `artifacts/opencode-android-full-arm64-native-log-debug.apk` 已安装 K30，629558884 字节，SHA-256 `b1cb812c8eeff44282429a2178bfc9a8736b5017a67b6219edbc369f063442c0`。测试包为 `opencode-android-native-log-androidTest.apk`，校验文件 `SHA256SUMS-native-log-arm64.txt`；属于本地调试交付，完整 M0–M5 验收和正式签名发布仍在进行。

## 2026-10-29：不同 UID 控制端的后台负载

[后台测试说明](BACKGROUND_TESTS.md)：控制端自插桩、工作站使用不同 UID，不向工作站绑定前台优先级。smoke 1 项、0.482 秒通过，signature 权限拒绝非签名 shell；实际后台负载 1 项、691.243 秒通过，包含独立 APK Debian 安装和 10 分钟基线/8 PTY 编译内存负载/空闲阶段。

40 个样本的工作站宿主 importance 均为 125；8 个终端持续存活、48 次 gcc 编译和程序输出完成，认证与原官方会话持续通过。空闲宿主 FD 120→120，宿主 PSS 45,724–46,893 KiB；进程组 RSS 有 40 个样本，完整进程组 PSS 无可用样本，明确保留缺失值。专用进程、前缀设置和测试 Linux 目录清理成功，正常用户环境未挂载或切换。

原始结果与摘要分别为 `.cache/evidence/k30-background-soak.txt`、`k30-background-soak.json`、`k30-background-soak-summary.json`。这台设备处于锁屏但 interactive=true、USB 充电状态；debug 构建、10 分钟有限负载不替代正式 release、LMK、真实续航、断电/息屏长期待机、其他系统版本/16 KiB/外设或可见 WebView 渲染验收。

最终主机 debug/release 构建、单元测试及两个模块的 lint 通过（`background-final-build.txt`，21 秒）。APK 实际证书与内置程序证明同 signer，SDK/ABI/16 KiB 包装静态检查通过；正式 release 排除诊断服务、signature 权限、协议类及测试组件。冻结 `artifacts/opencode-android-full-arm64-background-debug.apk`，629,837,708 字节，SHA-256 `e617c3b32a71368ab6cee65ee298c843e02fce2052dc465eea72b8e6d4aefc66`。独立测试控制 APK 与 `SHA256SUMS-background-arm64.txt` 同目录；它不是用户运行产品所需的依赖。交付记录 `.cache/evidence/background-delivery.json`。

## 2026-10-29：宿主进程死亡与中断恢复

[生命周期说明](LIFECYCLE.md)记录新的本机任务日志、通知自动启动保护及原生恢复入口。K30 记录层 8 项、0.685 秒通过（`.cache/evidence/k30-development-history-fixed.txt`）；实际 RuntimeService 在独立 Android 进程和全新 APK Debian 树上的宿主 SIGKILL 1 项、86.626 秒通过（`k30-host-process-death-final.txt`）。

实际过程包含 gcc 编译运行、官方认证会话、两个带运行中命令的真实 PTY。采样 9 个进程（包含宿主），强杀后均已不再运行；会话、源码、构建结果和执行计数保留。自动启动被拒绝，显式重启后读取同一官方会话，没有自动创建旧终端或重放输入。使用真实 PID、进程出生时间、状态及 signal 0 检查，采样失败不能作为退出证据。临时 Linux 树、测试前缀设置与专用进程均清理，未切换正常用户根目录或数据库。

此前进程名声明导致安装失败、进程采样假设和 TIME_WAIT 端口检查均造成未通过轮次，保留对应输出；修正后才计入通过。原生恢复面板新增两个 UI 测试，当前只完成编译，仍等待 K30 解锁后运行。此结果不代表 LMK、电池、长时间压力、其他系统版本或 16 KiB 真机已验收。

debug/测试/release 构建、单元测试、debug/release lint 通过（`lifecycle-final-build.txt`，17 秒）。debug APK 与内置程序证明同 signer，完整官方包摘要、ARM64/API 29–36/16 KiB 静态检查通过；release 排除独立进程及备份 debug 测试服务。静态脚本追加组件进程名检查，防止再次交付系统拒绝解析的声明。已冻结 `artifacts/opencode-android-full-arm64-lifecycle-debug.apk`，629,837,590 字节，SHA-256 `cfb533d892509ae24491006fdbb651139029f9d23a97073da879dd25bd20ff31`；测试 APK 与 `SHA256SUMS-lifecycle-arm64.txt` 同目录。交付范围及未完成项见 `.cache/evidence/lifecycle-delivery.json`，不是正式发布验收。

冻结版已安装，最后记录层 8 项、0.676 秒通过（`k30-lifecycle-delivery-native.txt`）；测试后已请求打开正常浏览器 profile。系统仍显示 `deviceLocked=1`，没有据此声明可见界面或浏览器 API 连通验收通过。

## 2026-10-29：签名 HTTPS 更新源与下载

- K30 Android 16 / ARM64 / 4 KiB，下载器与包验证共 14 项、6.709 秒通过：RSA/EC 发布者锚、帧边界、元数据策略、HTTPS 重定向/降级与循环、内容编码及长度、截断/篡改/超长包、取消清理、旧候选保护、包内证明一致性；真实回环 TLS 验证默认平台信任拒绝未知 CA，并验证测试专用信任下的完整传输及无浏览器认证头。证据 `.cache/evidence/k30-update-https-tests.txt`。
- 实际 `dataSync` 前台服务与真实生产工具追加 3 项、0.274 秒通过：无效源不更改活动选择/已保存地址；TLS 握手中取消释放操作；Python/JCA 生成的源文件与已安装 APK signer、内置官方完整程序证明一致。证据 `.cache/evidence/k30-update-fetch-service.txt`。合成下载包没有执行或激活，正常用户的程序、会话和项目没有切换。
- debug/测试/release 构建、主机单元测试、debug/release lint、ARM64 API 29–36 与 16 KiB 包装/ELF 检查、实际 APK signer 与嵌入证明一致性通过。源生产 Python 测试 2 项、CI 元数据测试 3 项、签名/草稿脚本 Bash 语法及 actionlint 1.7.7 检查通过；测试 TLS 身份不在主应用 debug/release APK 中。官方前端仓库 tracked status 为空。
- 已安装并保留 `artifacts/opencode-android-full-arm64-online-update-debug.apk`（628,936,686 字节，SHA-256 `f5c8b1a42bb1e87ce1a7d06ff4a42c57628a259543fc99ab1fb1949864bd71cc`）；对应测试 APK 与 `SHA256SUMS-online-update-arm64.txt` 同目录，旧制品保留。详细交付记录 `.cache/evidence/online-update-delivery.json`。
- 可见原生更新页尚待 K30 解锁回归；最新检查仍为 `deviceLocked=1`，没有将底层通过计为界面通过。测试后已重新打开正常 profile。尚无远程仓库、正式签名环境、公开更新源或 GitHub 托管 CI 运行，本次不计为线上发布验收；跨 Host/schema、16 KiB 真机及其余 M5 项目仍待完成。

追加 AndroidBridge `android-preview`：K30 helper/IPC4项加真实预览2项，6项5.453秒通过，
证据 `.cache/evidence/k30-preview-bridge-final.txt`。PreviewTarget 本地策略现在5项通过。
审批点击到预览 Activity 的 UI 用例已补写，因仍锁屏没有执行通过记录。
正常用户配置只读诊断 `.cache/evidence/k30-model-metadata.txt`：凭据0条、配置文件0个、
提供商覆盖/默认模型字段0；没有读取凭据值或输出端点/密钥。该诊断不算模型连接/推理验收，
已询问用户模型选择，其他模块继续推进。

最新开发构建增加 LSP 外部目录→read 审批、符号链接改指向保护、配置文件插值，以及独立进程的
项目网页预览。K30 外部 LSP 4项通过；Agent/LSP/MCP/目录8项通过；最新预览+外部LSP 5项20.497秒、
真实 Debian Python 预览+原有LSP 5项19.158秒通过。Linux Node测试12项、预览策略JUnit4项通过。
开发APK已安装，具体见 [LSP.md](LSP.md)、[PROJECT_PREVIEW.md](PROJECT_PREVIEW.md)。
K30仍锁屏；目录入口和预览启动器的触控、视觉、旋转及组合界面验收尚未完成，artifacts保持上一版。

上次完整工作区/生命周期界面回归（稳定M4制品）：自适应3、真实Hybrid1、连接/全屏6、文件UI3、PTY1、Bridge7，21项通过（60.633秒）。
该阶段正常用户 profile 冷启动后，前端自身的本地 Cookie API=200/root873；该历史结果不代表本轮锁屏下的新UI验收。早期段落中的
待验收项为当时记录，后续结果见末尾补充及 [BROWSER_CONNECTION.md](BROWSER_CONNECTION.md)。
ARM64 真实16 KiB与完整设备矩阵仍未通过，不能由静态对齐检查推定完成。

自适应工作区的阈值、固定面板节点、真实K30双服务、窗口PixelCopy画面及未覆盖设备范围见 [WORKSPACE.md](WORKSPACE.md)。本轮也修复桌面停止后因startId变化导致Service/Bridge lease残留。最终证据为`.cache/evidence/k30-adaptive-final-regression.txt`、`k30-hybrid-presentation.png`及`k30-adaptive-live-connection.jsonl`。

## Redmi K30 5G / Android 16 / ARM64 / 4 KB

真机 serial `<serial>`，API36、4096 字节页，应用 UID10333，targetSdk36。未使用 root、外部 Termux、旧 SDK 或 SELinux/Android seccomp 策略修改。

最新开发套件回归：**7 项通过，1 项网络检查明确跳过，0 项失败**，35.098 秒。JUnit 输出 `OK (8 tests)` 包含跳过项，不能把它写成 8 项全部通过。原始输出：`.cache/evidence/k30-developer-regression.txt`。

- APK 原生目录启动 PRoot/Loader、Debian glibc/Bash、shebang、fork/exec/wait/signal、`/proc/self/fd`、workspace 映射通过。
- JNI 控制 PTY、尺寸设置，以及 tracer 退出后终止独立进程组的 tracee 通过。
- 官方 OpenCode `2.1.4-dev.f993a89d13c7` 本机服务、匿名拒绝、认证、一次性配对/Cookie、配对重放拒绝、官方 WebView 实际页面高度通过。
- xterm 输入桥、实际 Bash 输出、行列同步、Ctrl+C 中断前台程序和独立双会话通过。
- 内置开发套件可用：curl 8.14.1、wget 1.25.0，Git/gh/SSH，GCC/G++/Clang、CMake/Ninja，Node/npm、Python/pip/venv、Rust/Cargo 及辅助工具均存在。
- GCC 多文件动态链接和静态链接程序、Clang 构建、C++、Node 子进程、Python subprocess/venv/pip、Rust/Cargo 离线编译运行通过。Python 直接验证 close_range 返回 ENOSYS 后使用正常回退。
- rootfs 升级保留旧完整备份，workspace、home、OpenCode 数据探针保留；中断切换恢复、完成后保留旧备份、拒绝不安全恢复路径通过。首次升级与恢复验证另有 2 项通过，79.445 秒。

初始最小镜像没有 curl/wget；现已替换为含 629 个软件包的预装开发镜像。曾发现 Python 配置在 vfork 子进程中挂起；PRoot 新增 close_range 已知调用映射及 ENOSYS 兼容处理后，dpkg 配置和上述真实构建通过。旧测试遗留进程已经发送 SIGKILL 仍在内核运行，故重启测试机清理；重启解锁后上述回归通过，无新增遗留进程。

**联网验收单独记录**：此前官方 CDN 连接出现间歇超时，Android 与 Debian 可解析到同一地址，后续对照测试两侧均出现超时。不能据此把工具安装成功写成外网下载全部通过。APT 更新/下载仍需完成当前网络下的独立验证。

## Android 15 / API 35 / x86_64 / 4 KB 工程诊断环境

APK `targetSdk=36`，普通应用 UID，无 root、无 Termux。专用模拟器 `OpenCode_API35_4K`。

`LoaderAcceptanceTest`：**3 项通过，0 项失败**，130.171 秒。

- 官方 Debian 13 启动，glibc 2.41、动态 Bash、shebang、`/proc/self/fd`。
- fork、exec、wait、退出状态与信号；共享 `/workspace` 路径映射。
- Android JNI 创建控制 PTY，Debian 识别 TTY，`stty size` 返回指定行列。
- apt 从官方 Debian 源安装 GCC、libc 开发包、make、Python、CA 证书。
- make 构建多文件 C 工程，实际运行动态与静态 ELF，验证 fork/exec/waitpid。
- Debian Python 再验证 PTY、尺寸设置和子进程信号。

`OpenCodeAcceptanceTest`：**1 项通过，0 项失败**，6.123 秒。

- 从 APK 安装官方 dev `2.1.4-dev.f993a89d13c7`，完整 Linux glibc 二进制。
- Android 前台 RuntimeService 启动本机服务，版本身份匹配。
- 匿名 `/api/info` 返回 401；认证请求成功。
- `/api/pair` 签发配对码，首次兑换返回官方会话 Cookie，重放返回 401。
- 服务返回同次上游构建内嵌的官方 WebUI HTML。
- 后续 WebView 回归测试通过（4.834 秒）：发现并修复 Android WebView 的 `WRAP_CONTENT` 导致 CSS vh/dvh 为 0 的容器问题。将 Android View 的 LayoutParams 明确设为 MATCH_PARENT，未修改官方前端源码；实际 HTML 的 `#root` 高度大于 100 CSS px。

这还**不等于 M1 全部通过**：尚需完成模型连接、Agent 修改文件与执行测试、会话恢复、LSP/MCP/插件等完整流程，以及完整的视觉与交互验收。

## Android 16 / API 36 / x86_64 / 16 KB 诊断环境

专用模拟器 `OpenCode_API36_Diagnostic`，`getconf PAGE_SIZE = 16384`。

Debian 安装成功；动态程序与 PTY 的 guest 启动**失败**：

```text
/usr/bin/env: error while loading shared libraries: libc.so.6:
ELF load command address/offset not page-aligned
```

原因定位：官方 Debian amd64 glibc 的 PT_LOAD 对齐为 4096；ARM64 glibc 的 PT_LOAD 对齐为 65536。此 x86_64 组合不能代表 ARM64 16 KB 验收结果。未降低 targetSdk，未修改 Android 安全策略，未将失败写成通过。

## ARM64 静态检查

- PRoot 和 Loader 已从固定源码提交以 Linux NDK r29 构建。
- PRoot、Loader 和 OpenCode Linux ARM64 完整二进制均通过 16 KB ELF 段对齐检查。
- Debian ARM64 glibc PT_LOAD 对齐为 65536。
- JNI 宿主代码显式使用 16 KB 链接标志，实际页面大小通过 `sysconf(_SC_PAGESIZE)` 读取。

**ARM64 4 KB 真机已验证，ARM64 16 KB 实际设备仍未验证，M0 尚未整体完成。** 不能用静态对齐或 x86_64 结果替代 ARM64 16 KB 验收。

## 多会话终端集成

`TerminalAcceptanceTest`：**1 项通过，0 项失败**，7.508 秒，Android 15 x86_64 / 4 KB。

- APK 本地 HTTPS 资产加载器加载固定版本 xterm.js 与 fit addon，不访问 CDN。
- 测试通过 xterm 的输入事件触发 JavaScript → JNI PTY → Debian Bash → xterm 输出链路。
- 测试命令使用十六进制 printf 字节，输出断言不依赖 Shell 对输入命令的回显。
- xterm 的行列数与 Debian `stty size` 一致。
- Ctrl+C 中断真实前台 `sleep`，随后可以执行下一条命令。
- 同一运行时可创建两个独立终端；终端 WebView 禁止文件与 Content URI 访问。

中文输入法、触摸选择、完整 TUI、物理键盘/鼠标及长时间多会话仍需设备交互验收，未视为整个 M3 完成。

## K30 全屏、文件选择与文件管理

新增 Android 16 实机回归：全屏/悬浮球拖动/旋转/退出确认、网页弹层 Escape 返回和网页历史返回、实际图片字节上传、多文件上传、取消后重选共 **5 项通过，12.146 秒**。文件选择测试使用独立 WebView profile 和只提供合成样本的测试 Documents Provider，未清除用户 WebUI Cookie。真实 OpenCode 与终端追加回归 **2 项通过，7.788 秒**。

文件仓库 **3 项通过，0.159 秒**：导入重名保留、文本保存与外部修改冲突、重命名/回收站恢复、路径穿越和符号链接防护、失败导入不留半成品、二进制导出保持字节。Compose 文件页 **1 项通过，7.686 秒**：新建、编辑保存、旋转保留未保存草稿、返回确认和目录返回。

发现过一次 rootfs 内容不完整，未删除原目录；用内置摘要校验 pack 显式修复并保留备份。修复/中断恢复 **2 项通过，69.326 秒**，随后只读诊断确认 `.installed-pack`、开发清单、curl、Bash 和完整 OpenCode 均存在。没有将先前不完整环境误报为健康。

## K30 apt 和图形桌面

通过中科大 HTTPS 镜像的签名 Debian apt 索引安装桌面组件，**1 项通过，186.065 秒**。包括 XFCE 4.20、TigerVNC、noVNC 1.6.0、websockify 0.12.0 和 Chromium 156.0.8078.47。未使用不安全 apt 参数，未更换 Android 安全策略。

桌面窗口管理器就绪、匿名网关 401、认证 HTTP 200、带凭据的 WebSocket/VNC 握手、实际非空画面、页面切换后重连及停止清理，**1 项通过，11.803 秒**。截图通过本应用的 Compose 根节点采集；早期只有背景的截图没有作为完整桌面证据，后续增加像素颜色多样性及视觉帧等待，已看到真实 XFCE 面板和桌面图标。

Chromium 标准 Linux 沙箱启动 **失败，退出 133/SIGTRAP**，不能记为通过。专用 about:blank 兼容探针使用 `--no-sandbox`、禁止后台联网和独立测试目录，实际 HTML 渲染与退出 **1 项通过，2.219 秒**。这没有启用产品默认兼容模式，也不能代替完整浏览器沙箱/网站/GUI/外设矩阵验收。详情见 [文件和桌面说明](FILES_AND_DESKTOP.md)。

随后增加真实 Chromium GUI 验收：在专用测试 profile 中打开本地 data URL，VNC 画布检测到测试页的绿色区域（1328 个采样点、260 种颜色），页面切换后仍显示同一个浏览器，**1 项通过，12.525 秒**。远程桌面尺寸从初始几何自动调整至 392×820，匹配 WebView 的 393×821 CSS 视口；应用自身截图中可见真实 Chromium 窗口和 XFCE 面板。该测试明确采用兼容参数，没有启用用户的持久兼容设置。

再加入原生点击“浏览器”设置、取消保持原模式、旋转为横屏后重连，**1 项通过，11.026 秒**。随后核心/文件合并回归 **6 项通过，14.347 秒**；补充根目录自身被替换为符号链接后的拒绝访问检查，文件仓库 **3 项通过，0.127 秒**。最新 ARM64 APK 和测试 APK 已复制到 `artifacts/`，SHA-256 见 `artifacts/SHA256SUMS-arm64.txt`；宿主全部 ELF 的 16 KB 对齐、APK P16、SDK29/36和官方OpenCode资产摘要检查通过。ARM64真实16KB仍未验收。

## K30 文件复制/分享和 AndroidBridge（2026-10-28）

文件复制、链接/执行权限保留、移动重名保护、禁止复制到自身、取消复制不发布半成品，以及跨 Android UID 读取分享副本而拒绝写入和越界，合计 **6 项通过，0.472 秒**。导入重复文件名也保留原扩展名。

AndroidBridge 与文件界面合并 **9 项通过，21.225 秒，无跳过**：真实 Debian xdg-open 到原生用户确认和 BROWSABLE Intent；剪贴板写入/读取和取消码130；无效令牌、非 HTTP(S) URL 和带用户密码 URL 拒绝；其他UID不能调用；真实 Linux helper 经 SAF 导入和导出并核对实际字节；分享选择器；helper断开撤销请求；两个持有者独立生命周期及末次停止删除认证文件和监听线程；后台请求不自动打开浏览器；用户确认的本地通知；文件界面复制/移动/分享与原有草稿旋转保护。浏览器、系统选择器和分享接收应用的界面结果使用 Intent 测试桩；文件字节、跨 UID 授权和实际系统剪贴板/通知真实执行。没有对外发送项目文件或登录真实账号。

测试 Documents Provider 的读和写均受 MANAGE_DOCUMENTS 保护，合成文件仅通过显式 URI 授权访问。测试控制 IPC 独立为另一个 Provider，限制调用方为目标应用 UID；生产 APK 不包含这些测试 Provider。

随后当前代码回归 **8 项通过，9.460 秒**（文件仓库、跨UID分享、真实官方服务和终端）；上传/全屏/返回 **5 项通过，12.371 秒**；加入桥接持有者后的真实 XFCE/Chromium 本地 GUI、切换/旋转重连 **1 项通过，12.847 秒**。构建和 lint 通过。

本阶段未增加广泛存储权限、未提供普通网页系统桥、未改变 Android 安全策略。完整规格中的其他系统版本、长时间任务、真实账号 OAuth 和压力测试仍需继续。

## 尚未验收

完整模型/Agent 工程开发和恢复，GitHub 登录/远程推送，LSP/MCP/插件矩阵，终端和桌面 IME/外设，SAF 原生界面完整交互矩阵与 AndroidBridge，Chromium 标准沙箱及网站兼容，平板多面板、自由窗口，ARM64 16 KB 与 Android 10–16、生命周期/内存/电池压力，完整备份恢复与发布签名。

可重复执行的测试命令见根目录 README。原始测试输出暂存于 `.cache/evidence/`；对外发布前需整理为去敏且可追溯的测试附件。
# 2026-10-28 最新补充

K30 当前 ARM64 APK 已加入实际 Agent/MCP/LSP 与浏览器连接恢复验证，旧段落是历史记录。

- 官方 Agent 真实四文件工程、失败测试→修复→重测、本地 Git commit/push、权限 once/reject、
  服务强制退出/重启后的 7 个消息 ID 及继续上下文通过；明确使用本地脚本协议模型，不冒充云模型。
- MCP stdio、Streamable HTTP JSON/SSE 响应、外部 Promise 插件工具/hook/RPC 通过。
- 按需签名 apt 安装 clangd/pylsp/pyflakes/pycodestyle 通过。真实 C/C++ 和 Python LSP 语义诊断、
  写入反馈、修复后清空、定义、引用、hover/符号、相对读取拒绝及进程组/退出清理通过。
  Native 插件安装保护也通过，最新 Agent/LSP/MCP 合计 7 项 32.211 秒；主机协议测试另 6 项通过。
- 用户现场 WebView 诊断证实有 Cookie 却 API=401，已改为验证浏览器身份并自动重新配对。
  失效 Cookie + 已消费链接恢复到页面 API=200；连同全屏/上传/返回键共 6 项 17.350 秒通过。

详见 [AGENT_ACCEPTANCE.md](AGENT_ACCEPTANCE.md)、[LSP.md](LSP.md)、
[BROWSER_CONNECTION.md](BROWSER_CONNECTION.md)。实际云模型/账号、完整 LSP 外部文件审批与
配置插值/操作覆盖、平板多面板、ARM64 真 16 KiB、完整 Android 版本/外设/压力及签名发布仍未全部验收。

## 2026-10-28 Android 容器渲染与系统外观

仅修改 Native Host，官方网页及终端/桌面网页资产保持原样。官方 WebView 在切页和方向/尺寸变化时保留实例，闲置缓存数量有界，系统深浅色资源和悬浮球不透明度设置已接入；默认优先请求 60 Hz，可在运行环境页切换为跟随系统。

K30 的 25 项组合回归共 66.530 秒通过，覆盖保留真实文档状态、方向切换、透明度持久化、两种原生主题下的 WebView 媒体查询、帧率设置、全屏/附件/返回、布局、真实双面板桌面、文件 UI、8 项桥接 UI，以及浏览器 Cookie 失效和已消费链接后的 API=200。项目目录完整 UI 链路另 2 项、17.680 秒通过。详见 [渲染记录](WEBVIEW_RENDERING.md) 与 [目录衔接](PROJECT_DIRECTORIES.md)。

此前 20 项首次回归有 4 项测试失败：文件夹测试未隔离当前项目目录，预览确认测试在等待异步 Compose 分发前阻塞了测试时钟；修正测试准备/等待后，上述 25 项通过。目录组合测试另修正了非主线程读取 WebView 和模块未加载完成便提前点击的问题，没有用降低断言要求替代实际界面验证。

早期加载样本和后续稳定样本的场景不同，未据此声称整体性能提升比例。实际长对话、流式输出、软键盘和系统回收压力仍需专项采样。

随后预览模式跨进程传递与隔离专项合计 6 项、13.147 秒通过（`.cache/evidence/k30-native-render-preview-final.txt`）。正常用户 profile 已恢复，浏览器自身 Cookie API=200；最终 APK 的官方嵌入包摘要、ARM64、SDK 合约与 16 KiB 静态打包检查通过。这没有替代 ARM64 16 KiB 系统上的实际验收。

## CI 与独立更新验证层

新增 push/PR 构建、固定运行包缓存和标签签名/Release 草稿工作流。actionlint 与 Bash 语法检查通过；官方固定提交的跨平台 ARM64 构建、551 个 deb 离线验证、release APK 构建/单元测试/lint/SDK 与 ABI 检查通过。使用测试 keystore 完成了 APK 实际签名验证和更新包生产端演练；3 项 CI 版本/元数据集成测试通过。目前没有远程仓库或 GitHub 托管执行证据，不能据此声称云端 CI 已跑通。

K30 更新包验证层新增 7 项、9.458 秒通过，含以已安装 APK 证书验证完整官方程序、真实 gzip/ELF 暂存与 RSA/EC、篡改、格式、边界、版本等拒绝路径。之后已接入原生导入/确认、维护互斥、状态快照、原子程序选择、可恢复日志与回退。完整官方运行包在独立 namespace 中完成真实服务启动、既有测试会话跨更新读取、数据库逐字节回退及重启读取，1 项、12.211 秒通过；正常用户状态未切换。该包与基线属于同一官方 dev 版本，不构成不同 schema 之间的迁移证明。详见 [CI](CI.md) 和 [独立更新包](OPENCODE_UPDATES.md)。

事务安装与原生缓存/渲染该轮共 21 个不同测试通过，包括连续更新回退、损坏旧程序拒绝、确认后包体身份绑定、目录各切换点中断恢复、外部状态链接拒绝、旧 worker/资源清理与 Cookie/localStorage/无关缓存保留、原生 picker/审阅/运行中禁用/取消确认，以及真实官方服务和 PTY。该轮制品为 `artifacts/opencode-android-full-arm64-update-debug.apk`；构建/单元测试/lint 和静态 APK 检查通过，SHA 见同目录 `SHA256SUMS-update-arm64.txt`。当时正常 profile 已恢复，浏览器 Cookie API 200，可见官方页面 956 个 DOM 节点；没有清理用户浏览器资料。下面继续记录发布者基线与工作站备份的进展。

## 2026-10-29 发布者证明、加密工作站恢复和 Unicode 运行时

内置程序发布者证明随 APK 同 signer 验证，旧内置基线在首次独立更新前保留为带证明的独立程序。K30 上加密封套 5 项、更新事务/基线保留 8 项及实际完整官方运行包 1 项合计 14 项、89.112 秒通过；合成 ELF 只测事务而不执行。Windows 本地使用真实 unsigned release APK 完成证明注入、16 KiB zipalign、SDK debug 身份签名、apksig/同 signer 证明及包内容检查，拒绝已签名输入。证据 `.cache/evidence/k30-backup-crypto-publisher-baseline.txt` 与 `core-proof-release-signing-smoke.txt`。正式身份、真实不同 Host/schema 和云端 CI 仍未验收。

[工作站备份与恢复](BACKUP_RESTORE.md)在全新独立 Debian/developer kit 上通过 49,567 条目、1,707,046,423 明文字节与 650,135,709 加密字节的导出/认证暂存/整环境恢复/还原，265.886 秒。恢复后实际 gcc 编译运行、PRoot 链接写入、官方会话读取成功；较新的原环境工作和恢复后的新工作均保留，正常用户根目录/数据库未切换。证据 `.cache/evidence/k30-full-workstation-encrypted-final.txt`。

归档 7 项和实际 Native FGS 请求/保存/确认链路 1 项合计 8 项、122.285 秒通过，含 `000` 挂载占位、宿主配置注入拒绝及 content URI 保存字节一致；后台链路只使用 debug 专用注入的合成目录，release 不含该组件。恢复 8 项覆盖准备、提交、恢复自身中断和已确认还原的各故障边界，原生设置白名单 3 项通过。原生备份界面 2 项曾于 5.466 秒通过。证据分别为 `.cache/evidence/k30-backup-fgs-uri-final.txt`、`k30-full-restore-transactions.txt`、`k30-backup-native-settings-ui.txt`、`k30-backup-native-ui-final.txt`。

维护期间持有有超时的 CPU wake lock，等待确认/完成后释放。原生文件任务在协程启动前取得所有者，全部编辑器共同参与未保存内容检查；环境切换后的旧窗口即使另一个窗口已重开，也不能写回旧缓冲。JNI 改用标准 UTF-8 编码完整 Unicode，拒绝 NUL/孤立 surrogate；进程、PTY、宿主环境、真实 Debian emoji 工作目录及真实官方 Unicode 会话验证通过。最后组合文件协调 3 项、FGS 链路 1 项、Unicode 4 项共 8 项、20.442 秒通过，证据 `.cache/evidence/k30-backup-generation-unicode.txt`；Unicode 独立 4 项、1.456 秒见 `k30-native-unicode-complete.txt`。

后续可见界面回归遇到设备 Dozing/锁屏：20 项混合轮 16 项通过、4 项没有 Compose 层级；通过测试窗口的标准唤醒接口后，设备 Awake 但 keyguard 仍显示，8 项界面/运行时组合中 6 项失败（含被遮挡的 viewport/终端初始化）。没有把这些轮次记为通过，也没有降低断言。当前已请求手动解锁，等待本版可见界面和正常浏览器 profile 收尾回归。自动息屏设置仍为 2147483647，mStayOn=true；没有改变锁屏安全配置。

最终主机 debug/release 构建、单元测试、lintDebug/lintRelease 通过。当前 debug APK 的实际证书/内置程序证明、ARM64/API 29–36/16 KiB 静态检查通过；release 不含 debug 测试服务。最后文件协调/实际 FGS/真实 Unicode 进程与官方会话 8 项、19.710 秒通过（`k30-workstation-delivery-native.txt`）。已冻结 `artifacts/opencode-android-full-arm64-workstation-debug.apk`，628927306 字节，SHA-256 `ca7e457133e1e8fa21ee3b30c0cff27081103e74e28920c3749225d58e6440e9`；测试 APK 与 `SHA256SUMS-workstation-arm64.txt` 同目录。该调试构建已安装，未对外发布，仍标记可见界面收尾待解锁；旧制品保留。

# 2026-10-29：系统外观、i18n、输入法和工作区恢复

交付：`artifacts/opencode-android-full-arm64-system-debug.apk`（634996969 字节，SHA-256 `3f6973721003046e9527836c01539804ab489c5d638466b8873aadc48a08fac7`）；测试 APK 为 `artifacts/opencode-android-system-androidTest.apk`。校验文件为 `artifacts/SHA256SUMS-system-arm64.txt`，汇总为 `artifacts/system-appearance-validation.json`。

- K30 原生回归：26 项通过，1 项可选签名更新选择器测试跳过（未启用 `includeHostSignedUpdate`，专用签名夹具未部署）；不能把框架输出的 `OK (27 tests)` 解读为 27 项都执行通过。证据：`.cache/evidence/k30-system-appearance-final.txt`。
- 实际首次工作区与跨宿主进程恢复测试另行通过，87.599s；保留原会话、源码和编译结果，未重放终端命令。
- 输入法 4 轮展开／收起：Chromium 视口高度变化从每次 18–27 次降至每次 1 次。该指标不是帧率提升百分比。
- 68 个语言／地区版本，各 513 条原生文案；参数、品牌名、技术路径与资源完整性检查通过。英／日／阿可见界面与 RTL 真机检查通过。其余语言包含机器初译，未逐语言完成母语审校。
- debug／unsigned release 构建、两种 lint、13 项 JVM 和 17 项 Python 检查通过；实际 APK 的 ARM64、min29／target36、16KiB 静态打包／ELF 对齐及内置官方程序证明签名检查通过。物理设备仍为 4KiB，不构成 16KiB 真机验收。
- 新启动图标已接入 adaptive icon 与 Android 13 monochrome 图层；原生 Android 12+ 动态强调色和深浅色跟随系统。官方前端源码未改动。
- 实际交付 APK 的 Debian 清单在 `artifacts/debian-inventory-system-arm64/`，SPDX 官方 schema 校验通过。只覆盖 Debian 部分，完整发行审计与对应源码交付仍未完成。


## 2026-10-30 首次托管 CI 成功与分发材料追加

公开仓库 main 的 `2343ed3b486b4fb7d58746c434c958441613dfae` 已通过 [Actions 40928314682](https://github.com/B5-Software/opencode-android-full/actions/runs/40928314682)：ARM64 运行包、源码检查、实际 APK 编译/单元测试/lint/静态契约、485 个 Debian 源包的 2209 个文件，以及四个上游归档均完成。标签签名任务在 main 上按设计跳过；此记录不代表签名 Release 已发布或完整设备矩阵已通过。

随后追加本地分发材料验证：68 个唯一 release 运行库的二进制摘要与 POM/source JAR 配对，收集 4329 份原始说明/版权头；官方清单依赖超集的 Linux ARM64 production 输入产生 685 条记录，其中四项为明示的标准声明审查项；两份字体元数据和 r26b/r29 NDK 原始说明也按摘要配对。43 项 Python 测试、Android debug/release 构建、既有 JVM 单元测试、lintDebug/lintRelease 通过，实际两份 APK 的新增内嵌材料契约、ABI/SDK/对齐检查通过。npm SPDX 清单通过已固定的官方 2.3 Schema。证据在 `.cache/evidence/dependency-notices-*.txt`、`ci-canonical-export-tests.txt` 与对应 artifacts 下；新增分发流程仍需下一轮托管 CI 验证。

完整许可证适用性/兼容性结论、修改 WebKit/Bun 的重链接链路及 M0–M5 中其他设备/真实服务/压力验收继续保持未完成状态，不将材料收集与完整审计混同。

## 2026-10-30 首个正式签名工程预发布

`8d0c399fcddb3466f17bc01ae491920b601890fc` 的 main [Actions 40947115361](https://github.com/B5-Software/opencode-android-full/actions/runs/40947115361) 全部通过后，创建了 `v0.1.0-beta.1` 标签。[标签 Actions 40948942584](https://github.com/B5-Software/opencode-android-full/actions/runs/40948942584) 的源码、ARM64 运行包、APK 检查及正式签名发布任务均成功；[公开 Release](https://github.com/B5-Software/opencode-android-full/releases/tag/v0.1.0-beta.1) 为 prerelease，35 个资产均为 uploaded。

实际下载全部 35 个资产（2,690,988,263 字节），逐一核对 GitHub 资产摘要、两份 SHA-256 清单，验证正式 APK 签名/内置程序证明、更新包与更新源 RSA 签名、匹配的官方程序和源提交，以及 Maven/npm 内嵌材料和两份 SPDX 2.3 Schema。实际 APK 证书与仓库外保存的项目密钥证书一致：SHA-256 `76d8185709761f45e880fda75bf8f20674659484e4ac66e60f3cf80b9cd71b71`。

正式 APK 为 627,750,491 字节，SHA-256 `cdc35f96e865f55da5477934f752717be2c5ab2956ac47c8a901fbbd5a9ca9db`。下载证据在 `.cache/evidence/published-v0.1.0-beta.1-verification.json` 与 `published-v0.1.0-beta.1-apksigner.txt`；本地原始下载位于 `artifacts/release-v0.1.0-beta.1/`。工程发布保留 485 个 Debian 源包/2209 个文件、68 份唯一 Maven 配对以及 685 条 npm 清单超集的范围记录，四项声明审查仍显式保留。

这些是宿主与分发验证，不新增正式签名 APK 的真机安装证据；已安装的开发签名版本和用户数据保持保留。完整许可证兼容性、修改运行库重链接及其余 M0–M5 验收继续未完成。
