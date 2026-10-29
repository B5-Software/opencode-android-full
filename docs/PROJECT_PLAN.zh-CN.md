OpenCode Android Full

Android 16 现代化手机与平板完整移植项目计划书

项目版本： v2.0
编制日期： 2026 年 10 月 27 日
开发平台： Android 16 / API 36
目标设备： Android 手机、平板、折叠屏
目标 CPU： ARM64
Linux 发行版： Debian 13 ARM64
项目性质： 非官方开源移植

1. 项目目标

开发一款在现代 Android 系统上独立运行的完整 OpenCode 2 应用。

应用直接使用 OpenCode 官方 WebUI 和完整服务端，在 APK 内运行 Debian ARM64 用户空间，提供真实的 Linux 开发能力。

用户可以在 Android 手机或平板上完成 AI 编程、代码编辑、终端操作、GitHub 登录、项目构建和测试，并在需要时切换到 Debian 图形桌面，运行 Chromium 等 Linux GUI 程序。

1.1 不可妥协的技术要求

targetSdk = 36，compileSdk >= 36。

minSdk = 29，最低支持 Android 10。

不使用旧 Target SDK 绕过执行限制。

不要求 root、Magisk、Shizuku 或外部 Termux。

不使用第三方重写的 OpenCode 主界面。

完整后端在 Android 设备本地运行。

保留官方 Agent、Shell、PTY、LSP、MCP 和插件架构。

Debian 支持 apt 安装普通 Linux 用户空间软件。

手机和平板共用一套后端和 APK。

支持 XFCE、Chromium 及其他 Linux GUI 应用。

默认无遥测，不上传用户项目或使用行为数据。

模型推理可使用用户配置的云 API 或本地模型服务，不要求模型本身在手机上离线运行。

2. 现代化运行架构

2.1 系统结构

Android 16 Application
│
├── Android Host
│   ├── Kotlin / Jetpack Compose
│   ├── WebView
│   ├── RuntimeService
│   ├── Process Supervisor
│   ├── AndroidBridge
│   └── Secure Storage
│
├── Native Runtime (APK)
│   ├── libproot.so
│   ├── libproot-loader.so
│   └── native bootstrap
│
├── Debian 13 ARM64
│   │
│   ├── OpenCode 2
│   │   ├── Official WebUI
│   │   ├── Agent Server
│   │   ├── PTY / Shell
│   │   ├── LSP
│   │   ├── MCP
│   │   └── Plugins
│   │
│   ├── Developer Tools
│   │   ├── Git / gh / SSH
│   │   ├── Node / Python
│   │   ├── Rust / Clang
│   │   └── apt
│   │
│   └── Graphical Environment
│       ├── XFCE
│       ├── X11
│       ├── TigerVNC
│       ├── noVNC
│       └── Chromium
│
└── Android Platform Integration
    ├── OAuth / Browser
    ├── Clipboard
    ├── Storage Access Framework
    ├── Notifications
    └── Keyboard / Mouse / Touch

2.2 Android 与 Debian 的职责

Android Host 负责应用生命周期、系统权限、输入、窗口、通知及进程管理。

Debian 负责 OpenCode 2 后端、用户代码执行、包管理、编译器和图形化开发环境。

Android Host 不重新实现 Linux 包管理器、Agent 引擎或代码执行系统。

Debian 不直接操作 Android 特权接口，相关能力通过 AndroidBridge 提供。

3. 现代 Android ELF 执行方案

这是本项目最重要的底层模块。

3.1 不依赖 Target SDK 28

Android 10 起，面向 API 29 及以上的普通应用不能直接从可写应用数据目录执行 ELF 文件。

项目采用现代执行兼容架构，而非降低 Target SDK。

3.2 原生启动器

参考 DroidNative 已公开的 PRoot 与 ELF Loader 设计。

将 Android 原生可执行组件编译为 ARM64 ELF，并作为符合 APK 原生库打包规则的组件放入：

lib/arm64-v8a/
    libproot.so
    libproot-loader.so
    ...

对需要从文件路径启动的组件，构建时设置适当的原生库解压策略，并从 Android 提供的只读 nativeLibraryDir 获取实际路径。

PRoot 作为原生进程执行，由其 Loader 处理 Debian rootfs 内的 Linux 程序。

不得对位于应用可写目录中的 Debian ELF 直接调用宿主 Android execve()。

3.3 Debian 程序执行

Debian rootfs 解压至应用私有存储。

内部程序通过兼容加载器执行，例如：

Android Native Bootstrap
       ↓
PRoot + ELF Loader
       ↓
Debian glibc Runtime
       ↓
/bin/bash
       ↓
/usr/bin/opencode
       ↓
子进程 / 编译器 / LSP / MCP

重点验证：

动态链接的 Debian ARM64 ELF。

静态链接 ELF。

Linux shebang 脚本。

glibc 动态链接器。

文件描述符与 /proc 访问。

fork、execve、waitpid。

PTY 与 TTY。

信号及进程退出。

PRoot 路径重映射。

真实 Linux 构建工具的子进程链。

任何一个测试失败，都需要在 PRoot/Loader 层修复，不应通过降低 Target SDK 解决。

3.4 两条底层研究路线

主路线：DroidNative 风格 PRoot Loader

使用 APK 原生库目录中的启动器，通过 PRoot 加载 Debian 程序。

此方案优先保证 Debian glibc 兼容性，同时允许维持现代 Target SDK。

辅助路线：Termux system_linker_exec

研究其通过 Android 系统动态链接器执行 Bionic ELF 的方式。

这一机制可用于 Android 原生工具链，但不能直接等同于 Debian/glibc 程序加载方案，因此不作为 Debian 的默认启动实现。

4. Android 应用技术标准

4.1 SDK 与构建配置

初始版本建议：

android {
    namespace = "org.example.opencodeandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.example.opencodeandroid"
        minSdk = 29
        targetSdk = 36

        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}

以上包名为占位符，正式发布前确定。

主应用使用 Kotlin、Jetpack Compose、AndroidX 与现代 Android Gradle Plugin。

Native Runtime 使用 Android NDK 和 C/C++，构建可复现的 ARM64 原生组件。

4.2 16 KB 内存页支持

所有宿主 Native ELF、共享库、PRoot 和加载器都必须适配 16 KB 页面大小。

要求：

使用支持 16 KB 页面的 NDK 和链接工具链。

校验 ELF 段对齐。

校验 APK 原生库打包对齐。

在 4 KB 和 16 KB 页面设备上测试。

对 Debian glibc、Chromium、OpenCode Runtime 进行单独兼容性测试。

不得假定 Android 端原生组件通过 16 KB 测试，就代表整个 Debian 用户空间通过测试。

4.3 现代窗口适配

支持 Android 16 的 Edge-to-Edge 布局。

支持完整的 WindowInsets 处理，避免 OpenCode 的输入框或终端被导航栏、状态栏、手势区域和软键盘遮挡。

平板、折叠屏及自由窗口环境中，应用必须能够动态改变尺寸。

不依靠锁定横屏或固定窗口比例维持布局。

兼顾 Android 17 的大屏自由缩放行为，减少后续迁移成本。

5. OpenCode 2 官方前后端

5.1 官方 WebUI

直接使用上游 OpenCode 2 发布的官方 WebUI。

默认在 Android WebView 中加载本地 OpenCode 服务地址。

不得开发一套第三方聊天页面替换官方 WebUI。

允许在外层添加 Android 平台导航，包括：

OpenCode

Terminal

Desktop

Files

Runtime Settings

Android 容器与官方 WebUI 相互独立。

5.2 完整 OpenCode 后端

使用官方 Linux glibc ARM64 构建，版本与官方 WebUI 匹配。

后端运行于 Debian 环境，不采用裁剪的 Android Agent 仿制实现。

保留：

Agent 与 Session

工具调用

Shell

PTY

LSP

MCP

插件

Git

项目文件管理

模型提供商配置

权限审批

会话历史和状态持久化

5.3 本地服务

默认配置：

OpenCode Server
127.0.0.1:49374

Desktop Gateway
127.0.0.1:6080

VNC Server
127.0.0.1:5901

Project Preview
127.0.0.1:<dynamic>

PRoot 使用 Android 宿主网络栈，通常不需要 NAT 或额外的端口转发。

Android Runtime Manager 负责检查端口占用、服务启动、健康状态及异常退出。

官方 OpenCode WebUI 使用上游认证机制。Android Host 管理初次本地配对，但不禁用服务认证。

6. 手机端适配

手机采用触摸优先、单主视图的交互架构。

主区域显示官方 OpenCode 移动版 WebUI。

Android 容器提供最小化的导航层，不覆盖或重写原有 Agent 操作。

6.1 终端

提供可交互的 PTY 终端组件，支持：

多终端会话

Ctrl / Alt / Esc / Tab

Ctrl+C / Ctrl+D

TUI

输入法组合输入

命令复制、粘贴

软键盘自动避让

蓝牙键盘

横屏终端布局

终端与 OpenCode 后端使用同一 Debian 工作目录。

6.2 桌面

手机通过独立 Desktop 页面进入 Debian 桌面。

提供：

全屏显示

横屏及竖屏

触控模拟鼠标

双指缩放

鼠标滚轮模拟

虚拟键盘及组合键

分辨率切换

用户返回 OpenCode 时，桌面进程保持运行，除非主动关闭。

7. 平板端适配

平板采用自适应 Workspace 设计。

根据可用窗口宽度而不是简单的设备类型选择布局。

7.1 Compact Workspace

适用于小窗口或竖屏状态。

采用类似手机的单主视图结构。

7.2 Expanded Workspace

适用于横屏或大窗口。

支持：

官方 OpenCode 宽屏 WebUI

侧边项目文件管理

底部或右侧终端

可调整分割比例

键盘快捷键

鼠标右键及滚轮

7.3 Hybrid Workspace

支持官方 OpenCode 与 Debian 桌面同时显示。

例如：

┌─────────────────┬─────────────────┐
│                 │                 │
│ OpenCode WebUI  │ Debian Desktop  │
│                 │                 │
│ Agent / Diff    │ Chromium / GUI  │
│                 │                 │
├─────────────────┴─────────────────┤
│          Terminal / PTY           │
└───────────────────────────────────┘

布局能够自由调整比例，并记住不同屏幕尺寸下的用户设置。

7.4 外设支持

平板端将实体键盘和鼠标作为一级支持目标。

支持：

Ctrl、Alt、Shift 等组合键

鼠标左右键

触控板和滚轮

文本选择与拖动

分屏及多窗口

外接显示器基本兼容

窗口动态缩放及旋转

8. Debian 桌面与 Chromium

8.1 桌面技术

首版：

Debian XFCE
    ↓
X11
    ↓
TigerVNC
    ↓
websockify / noVNC
    ↓
Android WebView

桌面按需启动，不随 OpenCode 自动启动。

XFCE 使用低动画和低资源配置。

Chromium 采用 Debian ARM64 官方软件包，支持用户通过 apt 升级。

8.2 桌面窗口

支持普通 Linux GUI 操作，包括：

Chromium

XFCE Terminal

文件管理器

Git GUI

文本编辑器

其他 apt 安装的桌面软件

后续评估使用 Android 原生 Surface/Wayland 桥接，减少 VNC 帧编码开销。

8.3 Chromium 安全

Chromium 在 PRoot 环境中可能无法提供标准 Linux 桌面上的完整进程及命名空间沙箱。

不将关闭沙箱视为默认安全方案。

优先评估能够正常启用的安全机制；确需使用降级沙箱配置时，应提供明确警告和隔离策略。

重要账号的登录默认交给 Android 系统浏览器处理。

9. AndroidBridge 系统集成

开发统一 AndroidBridge 服务。

它通过受控 IPC 与 Debian 内部 helper 进行通信，不直接向普通 WebView 页面开放任意系统调用能力。

9.1 功能

Debian 操作

Android 实现

xdg-open URL

Android 浏览器

gh auth login --web

GitHub 浏览器认证

OAuth 回调

Loopback / App Link

文件导入

SAF

文件导出

SAF

剪贴板

ClipboardManager

消息通知

NotificationManager

应用密钥

Android Keystore

开发服务器

本地端口检测和预览

分享文件

Android Sharesheet

9.2 GitHub CLI

优先支持 GitHub 设备码登录流程：

用户在 Debian 运行 gh auth login --web。

AndroidBridge 检测或接收浏览器打开请求。

Android 系统浏览器打开 GitHub 授权页面。

用户完成登录、Passkey 或 2FA。

Debian 内 GitHub CLI 完成授权。

凭据保存于受控 Linux 用户环境。

允许用户选择在 Debian Chromium 中完成特殊网页登录，但不强制所有认证都通过桌面浏览器进行。

10. Android 现代后台管理

10.1 RuntimeService

开发专用运行时服务管理 Debian 与 OpenCode 子进程。

用户主动启动开发环境时，服务进入前台运行，并显示持久状态通知。

提供以下操作：

查看运行状态

暂停或停止开发环境

停止正在运行的任务

返回 OpenCode

查看运行错误

10.2 前台服务类型

根据 Android 14 及以上的规则选择合法的 Foreground Service 类型。

持续的用户可见开发会话可评估 specialUse，并提供明确的用途声明；该类型是否能通过 Google Play 审核需要独立验证。

不得把 dataSync 当作无限时长的通用运行服务，因为 Android 15 及以上对其存在后台运行时间限制。

10.3 生命周期

将 Android Activity、WebView 与 Debian 后端生命周期分离。

用户切换页面或旋转设备不应重启 Debian。

进程被系统杀死后：

保存已持久化的会话和项目数据。

检测失去连接的任务。

提供显式重启与恢复。

对无法恢复的任务明确标记中断。

不承诺在 Android 所有厂商系统下永久后台运行。

11. 数据安全与更新

11.1 数据组织

files/
├── rootfs/
├── workspace/
├── opencode-state/
├── desktop-state/
├── runtime-config/
└── backups/

Debian 内映射出稳定的 /home、/root 与工作目录。

11.2 隔离边界

用户项目默认保存在应用私有目录。

外部文件访问由 SAF 提供。

不默认开放监听到局域网的服务。

AndroidBridge 使用最小权限和来源认证。

不允许任意不受信任网页调用系统桥接。

不使用未经确认的远程可执行代码更新。

敏感凭据不进入公开日志。

11.3 更新

分别管理：

Android Host 与原生 Loader

Debian rootfs

OpenCode 2 官方前端与后端

Linux 软件包

OpenCode 官方前后端使用匹配的版本，不进行跨版本混搭。

更新前提供快照或备份，更新失败不得清除用户项目。

Android Host、原生 Loader 和其他安装包组件通过签名 APK 更新。

Debian 软件包的维护同时要考虑其代码执行风险及软件来源完整性。

12. 构建与分发

12.1 开发工具

Android Studio

Kotlin / Gradle

Android NDK

C / C++

Debian ARM64 构建环境

GitHub Actions

Android Emulator / ADB

12.2 发布形式

优先发布：

GitHub Releases 签名 APK

可复现构建源码

SHA-256 校验值

依赖与许可证清单

架构初期仅支持 ARM64。

可同时评估 F-Droid 与 Google Play 分发。

需要特别注意：使用 Target SDK 36 不代表自动符合 Google Play 所有政策。完整 Linux 包管理器、动态代码加载、可下载的可执行文件、前台服务用途均需独立进行政策与安全审查。

不为了商店审核而暗中阉割产品功能。

12.3 开源合规

参考 DroidNative 时遵守其 GPL-3.0-or-later 许可证及 PRoot 相应许可证。

对复用的 GPL 代码、Android Host、构建脚本、第三方程序及软件包进行许可证兼容性审查。

13. 现代化开发里程碑

M0 — Android 16 Loader Proof of Concept

目标：

在 targetSdk=36 的独立 APK 中成功启动 Debian 13 ARM64。

必须验证：

原生库目录执行宿主 Loader

PRoot 调用 Debian Bash

Debian ELF 与 glibc

apt 安装软件

fork / exec / PTY

16 KB 页面兼容

完成标准： 不降低 Target SDK，可以在 Debian 中运行真实的动态链接 Linux 软件。

M1 — OpenCode 2 完整服务

集成官方 OpenCode 2 Linux ARM64 构建。

完成 WebUI、本地认证、会话、Agent 和工具调用。

完成标准： 用户可以仅通过 APK 创建项目，让 OpenCode 实际修改文件和执行测试。

M2 — 完整开发工具链

验证 Git、gh、SSH、Node、Python、Rust、clang、LSP、MCP、插件及项目开发服务器。

完成标准： 完成至少一个真实的多文件工程开发、构建和 Git 推送流程。

M3 — 手机体验

完成 WebView、终端、软键盘、横竖屏、通知、系统登录和文件桥接。

完成标准： 无实体键盘也能完成常用开发操作。

M4 — 平板与桌面

集成 XFCE、VNC、Chromium。

实现 Expanded Workspace、Hybrid Workspace、键盘鼠标和窗口缩放。

完成标准： 平板能够同时运行官方 OpenCode WebUI 与 Debian 桌面。

M5 — 现代 Android 稳定性

完成：

Android 10–16 兼容验证

16 KB 页面测试

后台运行和系统回收测试

电池与内存压力测试

安全审计

备份恢复

签名发布

完成标准： 不依赖旧 Target SDK、root 或外部 Linux 环境，具备稳定的实际开发能力。

14. 必须完成的技术验收

Android 平台

Target SDK 36

无 root

无外部 Termux

无用户手动配置的 ELF 执行绕过措施

支持 16 KB 内存页面

支持 Android 现代窗口管理

兼容现代前台服务规则

Linux 运行时

Debian 13 ARM64

glibc 动态程序

apt 安装与更新

fork / exec / wait / signal

完整 PTY

Git / SSH / gh

Node / Python / Rust / clang

图形桌面及 Chromium

OpenCode

官方 OpenCode 2 WebUI

官方完整 Agent 后端

模型连接和认证

Session 恢复

Shell 与文件工具

LSP

MCP

插件

Git 集成

多会话及开发服务器

手机和平板

手机触控和软键盘

手机横竖屏

平板宽屏

自由调整窗口

键盘鼠标

OpenCode / Terminal / Desktop 切换

平板多面板工作区

GUI 与 Agent 共享工作目录

15. 风险边界

本项目以完整用户空间为目标，但不宣称能够无条件运行一切标准 Linux 软件。

PRoot 无法提供完整 Linux 内核隔离环境。

因此 Docker Engine、systemd、某些沙箱、内核模块以及依赖特殊设备接口的软件需要单独评估。

此外，Android 系统对后台进程、前台服务、内存使用和动态代码执行仍有约束。

这些约束必须通过可测量的兼容性测试确定，而不是通过降低 Target SDK、关闭所有安全策略或假定其他应用能够运行就推断本项目一定成功。

16. 最终项目定义

OpenCode Android Full v2.0 是面向现代 Android 手机和平板的本地 AI 软件开发工作站。

它将官方 OpenCode 2 WebUI、完整 Agent 后端、Debian GNU/Linux 开发环境与可选图形桌面整合为单一 Android 应用。

项目从第一阶段就采用 Android 16 Target SDK 36，通过 APK 原生加载器与 PRoot 运行 Debian，不依赖旧版 Android 执行权限行为。

最终产品应当允许用户仅凭手机或平板，完成项目创建、代码修改、命令执行、依赖安装、构建测试、GitHub 登录与代码提交的完整工作流程，并能够按需打开 Debian 桌面运行 Chromium 和其他 Linux GUI 程序。

最高验收标准：在 Target SDK 36 的无 root Android 设备上，官方 OpenCode 2、Debian ARM64、开发工具链与 Linux 图形桌面能够可靠协同运行，且手机和平板均具备完整的实际开发能力。

参考工程与技术资料

OpenCode 官方仓库

OpenCode 2 官方 WebUI 文档

DroidNative Android/Linux 集成

DroidNative 运行架构

DroidNative PRoot Fork

Termux 执行环境

Android 10 W^X 执行限制

Android 16 兼容性变更

Android 16 KB Page Size

Android Foreground Service Types