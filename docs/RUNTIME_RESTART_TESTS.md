# 实际后端重复退出与重启

`RuntimeRestartAcceptanceTest` 是显式开启的 ARM64 真机验收。它从 APK 安装独立 Debian/developer kit，使用实际 RuntimeService、官方完整 OpenCode、加密本地凭据及 PTY。正常用户的 rootfs、home、会话和项目不挂载到该目录。

单次测试循环 15 轮，依次覆盖主动停止、强制终止 PRoot、强制终止专用 Android 宿主，各 5 轮。每轮重新启动实际后端、读取同一官方会话、新开 PTY、删除旧产物后实际 gcc 编译运行，并检查项目与执行次数。退出后检查捕获的本 UID 进程及 PID birth/state，同时要求成功占用 127.0.0.1:49374；两项在同一 15 秒期限内完成。最后再启动后端并读取会话，验证没有重放旧终端命令。

诊断采样只记录计数和耗时。端口占用时，以 TCP 连接检查是否仍在监听；通过公开 `ParcelFileDescriptor`/`Os` API 统计插桩进程自身描述符中固定服务端口的本地/对端连接数，不读取 payload、其他端点、Cookie 或凭据。没有依赖读取系统网络表、root 或更改网络参数。

## K30 结果与端口窗口

Android 16 / API 36、ARM64、4 KiB，2026-10-29：最终 1 项实际测试、136.681 秒通过，包含独立安装及 15 轮。累计执行次数在每次启动后必须恰好等于已完成轮数，最后必须恰好为 15，防止将启动时重放计入新的基线。`k30-runtime-restart.txt` 保存原始结果，`k30-runtime-restart.json` 和 `k30-runtime-restart-summary.json` 位于 `.cache/evidence/`。

| 退出方式 | 轮数 | 信号/停止请求至进程与端口均释放 |
| --- | ---: | ---: |
| 主动停止 | 5 | 37–80 ms |
| 强制终止 PRoot | 5 | 64–522 ms |
| 强制终止 Android 宿主 | 5 | 65–100 ms |

最终 5 轮宿主退出中，4 轮在已捕获 PID 均不再运行后仍观察到短暂监听，进程状态变化与可预留端口之间最多相差 57 ms。首轮另 15 次诊断采样在 131.620 秒通过，观测到最大 60 ms 窗口，原记录保留为 `k30-runtime-restart-initial.txt` 及对应 initial JSON。这复现了单凭 PID 状态立即 bind 会失败的窗口。采样不证明具体内核线程或 socket 引用的归因，也不支持长期泄漏结论。

原 `HostProcessDeathAcceptanceTest` 改为在同一期限内同时等待全部捕获进程退出和实际端口预留，记录短暂监听次数，之后继续执行自动重启拦截、实际官方会话恢复、双 PTY 中断及不重放命令等断言。生产启动、认证和端口绑定逻辑沿用现有实现。

修订后的该项独立验收在 85.542 秒通过：捕获的 9 个进程于 58 ms 内不再运行，端口在 114 ms 可实际预留，期间重试 2 次；随后自动重启拒绝与显式恢复均成功，证据 `k30-runtime-release-deadline.txt`。

端口预留仍启用现有地址复用；Linux 对旧绑定程序及新绑定程序的 `SO_REUSEADDR` 有共同条件，复用设置本身不等于资源释放证明，参考 [Linux socket(7)](https://man7.org/linux/man-pages/man7/socket.7.html)。这里以实际预留和随后真实服务启动为验收。

## 复现

先构建并安装配套 debug 主 APK 与 androidTest APK，保持 ARM64 设备已授权 ADB。测试有意不接受任意进程号、目录或 shell 命令。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r -e isolatedBrowser true -e includeRuntimeRestart true -e class io.github.opencodeandroid.runtime.RuntimeRestartAcceptanceTest io.github.opencodeandroid.test/io.github.opencodeandroid.ui.IsolatedWebTestRunner
```

专用 `LifecycleProbeService.KILL_GUEST` 仅终止当前独立测试服务持有的 NativeProcess，无外部 PID 参数；该类在 debug 源集中、组件不对外导出。release 不含此辅助服务。本测试不证明 LMK、断电、电池/Doze、其他 Android 版本或 16 KiB 用户空间兼容。

配套已测 QA 制品冻结为 `artifacts/opencode-android-full-arm64-restart-qa-debug.apk` 与 `opencode-android-restart-qa-androidTest.apk`，校验见 `SHA256SUMS-restart-qa-arm64.txt`。QA 完成后，K30 已恢复此前的 native-log 日常调试版并请求打开正常 profile；设备仍报告锁屏，可见 UI/网页帧率另行验收。
