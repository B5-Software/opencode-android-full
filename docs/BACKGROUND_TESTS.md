# 独立控制端的后台验收

普通 app 内插桩会改变进程优先级，因此后台验收另用 `diagnostics-controller` APK。它的 instrumentation target 是控制端自身；工作站 APK 不作为该次插桩的 target。真机 smoke 已确认控制端 UID 与工作站 UID 不同，并通过签名 IPC 访问固定测试协议。控制端不参与正式产品运行。

## 运行

```powershell
.\gradlew.bat -PdiagnosticsController=true :app:assembleDebug :diagnostics-controller:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r -t diagnostics-controller/build/outputs/apk/debug/diagnostics-controller-debug.apk
adb -s <serial> shell am instrument -w -r -e includeBackgroundSoak true -e class io.github.opencodeandroid.diagnostics.BackgroundSoakTest#tenMinuteBackgroundWorkstationSurvivesEightCompilerMemoryWorkers io.github.opencodeandroid.diagnostics/androidx.test.runner.AndroidJUnitRunner
```

两个 APK 必须来自同一调试签名身份。默认 Gradle 配置不包含控制端模块，只有显式 `-PdiagnosticsController=true` 时启用。普通 release 不包含诊断服务、权限、协议类或独立测试进程。

## 边界

诊断入口只在 debug manifest 声明，并要求 signature 权限；每条 Messenger 请求再检查发送 UID 的签名。未取得签名权限的 shell 直接启动已被系统拒绝。协议只接受版本、固定动作和测试 UUID，不接收文件根路径、代码、命令或模型密钥。

开始前拒绝已有正常开发服务/桌面或其他测试进程，不停止用户服务。每次从 APK 新安装独立 Debian，工作区、home、服务配置、数据库、临时目录和链接目录均属于新测试树；不挂载正常用户根目录或数据。控制端绑定使用 WAIVE_PRIORITY/NOT_FOREGROUND；测试还检查工作站宿主的实际进程 importance，避免将 TOP/前台插桩优先级视为后台通过。

诊断最长 14 分钟执行并保留清理时间，控制端失联 60 秒后取消。通知可取消；它使用有限的 specialUse 测试前台服务，没有额外 CPU 唤醒锁，也不修改 Doze、后台限制、屏幕、Phantom Process 或其他系统安全配置。结束后先停止实际服务，再核对本次 UUID/UID/专用进程身份，清理测试进程和私有目录；不会依据旧运行记录 PID 杀进程。

## 场景与测量

准备阶段安装独立工作站并创建真实官方认证会话，随后约 10 分钟：

- 60 秒启动基线。
- 8 个真实 PTY 各编译/运行 6 次 C 程序，共 48 次。每个程序驻留并触碰 16 MiB 内存约 20 秒；并发峰值目标为 8 个工作负载，不通过少开终端或减少编译次数完成断言。
- 之后保持 8 个终端，观察空闲阶段。每 15 秒校验版本、认证、匿名 401、会话目录、终端未退出和采样进度。
- 采样宿主 PSS、堆使用、FD、importance、锁屏/交互和充电状态，并按本 UID 的父子关系采样工作站进程。RSS 合计包含共享页重复，不能当作唯一占用；PSS 缺失时写 null 和已测进程数，不记为零。CPU tick 只覆盖采样时仍存活的进程，不包括采样间退出的编译子进程。
- 核对 8 个终端均有 6 次成功输出，会话仍可读取，空闲阶段宿主 FD 没有持续增长。

原始结果随控制端 instrumentation 输出返回，不包含命令行、环境、用户代码或凭据。

## K30 结果（2026-10-29）

控制端独立 UID/签名 IPC smoke 1 项、0.482 秒通过。控制端 instrumentation 的 targetPackage 为自身，实际 UID 10342，工作站为 UID 10333；非签名 shell 启动诊断服务被系统拒绝。release manifest 与 dex 中均无诊断入口、权限、协议或测试组件。

后台场景 1 项、691.243 秒通过，包含约 80 秒独立 Debian 准备和 10 分钟运行。40 个样本覆盖 0–592,016 ms；宿主 importance 全部为 125（前台服务），没有使用 TOP 插桩优先级。8 个终端始终未异常退出，48 次实际编译和 16 MiB 内存程序全部完成；官方版本/认证/匿名拒绝/原会话持续通过。结束时专用进程和新建目录清理成功。

空闲阶段宿主 FD 为 120→120。宿主 PSS 样本为 45,724–46,893 KiB；工作站进程数 3–35，RSS 合计为 428,752–635,156 KiB，全部 40 个样本具有 RSS。完整进程组 PSS 没有可用样本（0/40），结果中保留 null，不能用 RSS 合计冒充 PSS 或唯一占用。这些范围是本场景观察值，不是设备内存上限或无泄漏证明。

全程锁屏、屏幕交互状态为 true、USB 充电（plugged=2）。没有关闭屏幕、改变常亮设置或主动进入 Doze，不能据此声称已覆盖真实断电/息屏待机。原始输出 `.cache/evidence/k30-background-soak.txt`，结构化原始结果 `k30-background-soak.json`，摘要 `k30-background-soak-summary.json`。

最终调试 APK 已安装，控制端测试 APK 已从 K30 移除（主机保留可重现制品），并请求打开正常 profile。最新 keyguard 仍显示 deviceLocked=1，可见界面和浏览器连接回归尚未据此计为通过。

本场景只覆盖这台设备、debug 构建和给定负载。USB 充电、常亮设置与锁屏状态会影响结果，不能用此数据推导电池续航。它也不代表系统 LMK、完整生产 release、长时间重负载、其他 Android 系统、ARM64 16 KiB 或浏览器可见渲染已通过。
