# Debian 开发套件

正式 ARM64 APK 使用预装工具链的 Debian 13，不依赖首次启动后在线 apt 安装。清单在 `runtime/developer-profile.json`，完整安装版本在 `runtime/developer-packages-arm64.tsv`。

当前镜像有 629 个软件包（基础系统和 551 个新增依赖），普通文件及 Android 硬链接回退预计 1.35 GiB，安装另需 OpenCode 二进制和临时空间。运行时清单记录最低可用空间；升级保留旧 rootfs，不清理用户项目或自动删除旧备份。

## 来源和验证

基础镜像固定在 `runtime/debian-arm64-v8a.json`。新增软件包固定到 Debian Snapshot `20261027T000000Z`，覆盖 trixie、trixie-updates、trixie-security 的 main 组件。

- `developer-source-lock.json` 固定 InRelease 与 Packages.xz 摘要。
- apt 在构建 chroot 内使用 Debian archive keyring 验证 InRelease 签名；不允许未签名仓库。
- `developer-deb-lock.json` 固定每个 deb 的 URL、大小和 SHA-256，下载后逐个校验。
- 原始软件包版权文件保留在镜像的 `/usr/share/doc/*/copyright`。
- 最终 pack 和版本清单分别计算摘要，由 APK 内清单固定。

## 构建流程

需要 Linux/WSL、GNU tar/gzip、QEMU ARM64 user-static 和可写的任务专属构建目录。脚本使用 `/root/.cache/opencodeandroid/developer-build/rootfs`，不会导出手机或用户项目数据。

先准备基础 rootfs、OpenCode 和终端资产。以下 PowerShell 命令通过 Windows 下载，WSL 仅使用本地镜像；这是当前开发机 WSL 直连网络不可用时采用的流程。

```powershell
python tools/prepare_runtime.py arm64-v8a
python tools/devkit_mirror.py indexes
wsl -d Ubuntu --exec /bin/bash /mnt/d/opencode-android-full/tools/devkit_rootfs.sh create
wsl -d Ubuntu --exec /bin/bash /mnt/d/opencode-android-full/tools/devkit_rootfs.sh resolve
python tools/devkit_mirror.py packages
wsl -d Ubuntu --exec /bin/bash /mnt/d/opencode-android-full/tools/devkit_rootfs.sh install
wsl -d Ubuntu --exec /bin/bash /mnt/d/opencode-android-full/tools/devkit_rootfs.sh export
python tools/bundle_developer.py
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
```

其他工作目录应相应修改 `/mnt/d/opencode-android-full`。ARM64 QEMU/binfmt 注册由构建辅助脚本管理；导出前必须卸载构建用 `/proc` 和 `/dev`。临时 file: apt 源及缓存镜像不会进入最终 pack，运行时恢复普通 Debian 源。导出时间上限固定，gzip 不含时间戳，构建日志清空；尚未独立验证字节级可复现性。

## 设备验收

`DeveloperPackAcceptanceTest` 在真实 ARM64 Android 上验证 curl/wget 可运行、C 动态/静态和 Clang 编译、多文件链接、C++、Node 子进程、Python subprocess/venv/pip、Rust/Cargo 离线构建，并验证升级后项目、home 和 OpenCode 数据保留。`InstallerRecoveryTest` 验证切换中断恢复及保留旧备份。

网络访问另行验收，不能由工具存在或离线构建通过推断。`NetworkAcceptanceTest` 需要显式 `includeNetwork=true`，对比 Android 和 Debian 访问同一 HTTPS 仓库；完整 apt 更新与安装由 Loader 网络验收覆盖。

PRoot 补丁增加 close_range 的已知调用映射并返回 ENOSYS，让 libc/Python 使用普通 close/fcntl 回退。K30 的旧内核在 vfork 子进程阻塞信号时遇到未识别调用会留下无法终止的工作进程；补丁后 Python 配置和真实构建链通过。未降低 SDK 或修改 Android seccomp/SELinux 策略。
