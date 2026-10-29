[English](README.md) | 简体中文

# OpenCode Android Full

[![GitHub 发布下载量](https://img.shields.io/github/downloads/B5-Software/opencode-android-full/total?label=%E4%B8%8B%E8%BD%BD%E9%87%8F "所有 Release 资产的累计下载次数")](https://github.com/B5-Software/opencode-android-full/releases)

[![点击下载 APK](https://img.shields.io/badge/点击下载-Android_APK-1c2820?style=for-the-badge&logo=android&logoColor=white)](https://b5-software.github.io/opencode-android-full/download.html?asset=apk)
[![下载内核升级包](https://img.shields.io/badge/点击下载-内核升级包-496332?style=for-the-badge)](https://b5-software.github.io/opencode-android-full/download.html?asset=core)

[多语言官网 · 一键下载](https://b5-software.github.io/opencode-android-full/) · 首次安装或更新 Android 宿主请选择 APK；内核升级包 `.ocaupdate` 一起更新匹配的 OpenCode 前后端。

非官方 Android 本地开发工作站，按 [v2.0 项目计划书](docs/PROJECT_PLAN.zh-CN.md) 实施。

本项目正在开发，**当前不是 M0–M5 全部完成的成品**。Android Host 使用 Kotlin/Compose，`minSdk=29`、`targetSdk=36`、`compileSdk=36`，正式运行包仅 ARM64。x86_64 是显式启用的工程诊断版本。

## 已实现和已验证

- APK 内的 PRoot/Loader 从 `nativeLibraryDir` 启动；Debian ELF 不直接交给 Android 宿主 `execve`。
- APK 内置摘要固定的 Debian 13 rootfs，安装到应用私有目录；SHA-256 检查、路径穿越防护、符号链接检查、临时目录与原子安装。
- ARM64 rootfs 预装开发套件，共 629 个已安装软件包：curl/wget/CA、Git/gh/SSH、GCC/G++/Clang/CMake/Ninja、Node/npm、Python/pip/venv、Rust/Cargo，以及搜索、编辑和压缩工具。首次安装无需联网下载这些工具。
- 显式更新 Debian 时先完整解压校验新环境，再保留旧 rootfs 备份并切换；workspace、home、OpenCode 和桌面数据独立保留。支持中断切换恢复。
- 原生 PTY 创建、控制终端、尺寸调整、进程组信号；前台服务与 Activity 生命周期分离。
- APK 内置 xterm.js 终端，最多 8 个 Bash 会话、Ctrl/Alt/Esc/Tab、Ctrl+C/D、尺寸同步与页面切换恢复。终端页面只加载受控 APK 资产，其输入桥不暴露 Android 系统 API。
- 官方 OpenCode **dev 分支 2.1.4**，固定提交 `f993a89d13c73ff6f6537d6b97e78e138a3ea363`。使用上游原样构建脚本，将同一提交的官方 WebUI 嵌入完整 Linux glibc 可执行文件。
- 仅监听 `127.0.0.1:49374`；强制检验匿名请求返回 401；采用官方一次性配对链接和会话 Cookie。Android Keystore 加密保存本地密码。
- WebView 不注入 Android 系统 JavaScript 桥；可写 Linux 数据和用户项目分开管理。
- 全屏显示、可拖动吸边的悬浮球导航、横竖屏；返回键先关闭 OpenCode 弹层或返回网页，退出应用前确认。
- 原生界面跟随系统深浅色，Android 12+ 使用系统动态强调色；提供 68 个语言／地区版本并默认跟随系统语言，支持 RTL，译文含机器初译。详见 [原生 i18n 与外观](docs/NATIVE_I18N.md)。
- 新的生成式品牌图标支持 Android 自适应裁切和主题图标。悬浮球不透明度可调并保存，半透明时圆外不绘制阴影。详见 [图标与生成提示词](docs/APP_ICON.md)。
- 官方 WebView 在切页和旋转时保留实例，默认优先 60 Hz，可改为跟随屏幕；输入法动画按目标高度调整视口，避免逐帧重排。详见 [WebView 渲染与外观](docs/WEBVIEW_RENDERING.md)、[输入法视口回归](docs/IME_VIEWPORT.md)。
- 启动时恢复最近确认的工作区与会话，首次使用 `/workspace/helloworld`。详见 [工作区恢复](docs/WORKSPACE_RESUME.md)。
- 前端 Cookie 失效时自动重新配对，保留原页面状态；使用浏览器自己的 API 请求验证连接，不以 Cookie 名称存在作为登录成功。
- 按窗口宽度选择单页、开发分屏或 OpenCode + 桌面；文件侧栏、底部/右侧终端、拖动分割线、比例记忆及实体键盘快捷键。仍可见的 WebView 保留实例与草稿。文件列表支持鼠标右键和触摸长按。详见 [自适应工作区](docs/WORKSPACE.md)。
- OpenCode 附件接入 Android 系统文件选择器，支持图片、多选和取消重选。文件页支持 /workspace 浏览、UTF-8 编辑、导入导出、重命名及回收站恢复。
- 可按需通过签名校验的 Debian apt 源安装 XFCE、TigerVNC、noVNC 和 Chromium。桌面独立前台服务，切换页面后保持；VNC 与网关仅监听本机，网关强制认证和 WebSocket 来源校验。
- 文件页支持复制/移动、进度与取消，以及只读副本的系统分享。AndroidBridge 私有 Unix IPC 将 Debian 的链接、剪贴板、通知和文件导入导出请求交给原生确认界面；后台请求等待用户返回处理。使用方法见 [AndroidBridge](docs/ANDROID_BRIDGE.md)。

实际设备验收证据和未完成项见 [验证记录](docs/VALIDATION.md)。Redmi K30 5G（Android 16 / ARM64 / 4 KB）已通过 Debian 启动、官方服务/WebView、终端和开发套件离线构建测试；ARM64 16 KB 真机仍待验证。

[运行中断与恢复](docs/LIFECYCLE.md)记录后端、桌面和终端的中断；通知入口在检测到未正常退出的会话后要求显式重启，不重放终端命令。K30 独立进程强杀验收已验证真实官方会话及项目保留，未替代系统 LMK、长时间后台、电池或其他 Android 版本测试。

[项目目录入口](docs/PROJECT_DIRECTORIES.md)已打通原生文件夹、官方会话 API、新 PTY 和桌面 Thunar；K30 上的按钮、官方页面路由、编辑器保护与桌面项目按钮组合界面验收通过。

[项目网页预览](docs/PROJECT_PREVIEW.md)已加入运行环境页面，使用独立进程和浏览数据目录。
K30 已验证网页实际连接 Debian Python 服务，预览与 OpenCode Cookie 双向隔离；启动器及窗口交互待解锁验收。

## 构建

首个正式签名的工程预发布为 [v0.1.0-beta.1](https://github.com/B5-Software/opencode-android-full/releases/tag/v0.1.0-beta.1)。35 个资产已实际下载，APK 签名、匹配的 OpenCode 更新包/更新源签名及源码校验值均已核对。项目签名证书 SHA-256：`76d8185709761f45e880fda75bf8f20674659484e4ac66e60f3cf80b9cd71b71`。该版本仍保留本文列出的设备和审计验收边界。

push/PR 的 APK 自动构建、标签签名及工程预发布配置见 [CI 说明](docs/CI.md)，实际托管构建状态见 [GitHub Actions](https://github.com/B5-Software/opencode-android-full/actions)。签名预发布的下载入口为 [Releases](https://github.com/B5-Software/opencode-android-full/releases)。工作流成功不代替完整设备验收；现有开发签名安装与正式签名可能不同，请保留原安装、先导出项目，不同签名间的备份迁移尚未确立。[OpenCode 独立更新](docs/OPENCODE_UPDATES.md)已接入签名 HTTPS 更新源、下载、本地签名包导入、原生确认、会话快照、启动检查和回退；发布者证明保留内置基线，支持后续 Host/rootfs 改变后独立验证旧程序。不同 Host/官方 schema 的完整矩阵仍待完成。

[工作站加密备份与恢复](docs/BACKUP_RESTORE.md)已加入原生运行环境页面，覆盖持久化 Linux 环境与项目/会话、独立暂存验证、确认后多目录事务恢复，以及保留较新工作的整环境还原。K30 已用全新独立 Debian 环境通过约 1.71 GB 数据的真实编译、会话和还原验收；WebView 网页存储不随 v1 备份跨设备迁移，更多设备/provider/生命周期矩阵仍待完成。

开发环境：JDK 21、Android SDK 36、Build Tools 36、NDK `26.1.10909125`（JNI 显式 16 KB 链接标志）、CMake 3.22.1、Python 3.11+、Git。PRoot 使用 Linux NDK r29、GNU Make 构建；Windows 可通过 WSL Ubuntu 执行。

```powershell
# 下载并验证固定版本的 Debian rootfs，默认正式构建只需要 ARM64。
python tools/prepare_runtime.py arm64-v8a
python tools/prepare_terminal.py
# 在 Linux/WSL 使用 ANDROID_NDK=<Linux NDK r29目录> 执行：
# bash native-host/build.sh arm64-v8a

# 通过官方源码构建 OpenCode，见 tools/build-opencode.ps1。
powershell -ExecutionPolicy Bypass -File tools/build-opencode.ps1 -Abi arm64-v8a
# 按 docs/DEVELOPER_PACK.md 构建干净的开发套件 rootfs 后：
python tools/bundle_developer.py
# 分发构建还需按 docs/DEPENDENCY_NOTICES.md 准备 Maven/npm 说明与源码材料。
# Linux/WSL：bash tools/ci_prepare_notices.sh
.\gradlew.bat assembleDebug lintDebug
python tools/check_apk.py app/build/outputs/apk/debug/app-debug.apk --abi arm64-v8a
```

上游/构建产物存放于 `.cache/` 与 `app/runtime-assets/`，不进入源码版本控制。正常构建不自动下载或替换用户设备上的可执行文件；原生宿主组件随 APK 更新。运行时组件由已签名 APK 中的固定资产提供。

ARM64 构建会检查开发套件元数据，缺失 curl/wget 和主要工具链时拒绝生成 APK。重新运行基础 rootfs 下载器会保留已打包的开发套件。

PRoot 构建脚本依赖的 talloc、SQLite 压缩包均检查源码中固定的 SHA-256。离线构建可预先解压至 `.cache/native/`。NDK r29 Linux ZIP 的 SHA-256：`4abbbcdc842f3d4879206e9695d52709603e52dd68d3c1fff04b3b5e7a308ecf`。

## 诊断测试

```powershell
python tools/prepare_runtime.py x86_64
# 构建 x86_64 PRoot、OpenCode 后：
.\gradlew.bat assembleDebug assembleDebugAndroidTest -PdiagnosticX86=true
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -e includeNetwork true io.github.opencodeandroid.test/io.github.opencodeandroid.ui.IsolatedWebTestRunner
```

网络测试需显式传 `includeNetwork=true`。桌面联网安装另需传 `includeDesktopInstall=true` 并指定 `io.github.opencodeandroid.desktop.DesktopInstallTest`。上传界面测试需传 `isolatedBrowser=true`，使用独立 WebView 数据目录，保留用户的官方 WebUI 会话。

16 KB 的 **x86_64 Debian glibc** 无法加载普通 Debian 的 4 KB ELF 段。本项目不将此诊断组合当作 ARM64 16 KB 的替代验收。ARM64 glibc 和宿主 ELF 的静态对齐通过，不代表真实 ARM64 16 KB 系统已验证。

## 仍需完成

ARM64 16 KB 实机验证；真实模型驱动的 Agent 工程开发与恢复；终端和桌面 IME/TUI/外设交互矩阵；真实 GitHub 登录/远端推送、LSP 配置与语言覆盖；AndroidBridge 长时间/系统回收压力与完整文件操作矩阵；桌面 Chromium 沙箱兼容与隔离评估；真实平板、折叠屏和外接显示器的多面板验收；跨设备/provider/长期压力下的备份恢复矩阵；Android 10–16 生命周期与压力验证；持续发布验证与完整许可证兼容性审计。

本项目以 GPL-3.0-or-later 提供新代码。参考与复用组件的版权、许可证见 [第三方说明](THIRD_PARTY_NOTICES.md)。OpenCode 保留上游 MIT 许可证，Debian 内各软件包保留其各自许可证。

APK 内嵌 Maven/npm 说明归档，发布材料还包含实际 Android 运行库的完整 source JAR、OpenCode 清单依赖超集与原始字体署名。仅有许可证声明的条目及未完成审查范围见 [依赖材料](docs/DEPENDENCY_NOTICES.md)，不将工程预发布等同于完整审计通过。
