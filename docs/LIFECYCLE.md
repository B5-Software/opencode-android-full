# 运行中断与显式恢复

Android Activity、官方 WebView 与用户启动的 RuntimeService/DesktopService 分别管理生命周期。切页、窗口变化及 Activity 重建不停止开发服务；用户退出时可以选择保留后台环境。开发服务继续使用 specialUse，在线更新的有限传输使用 dataSync；没有开机自动运行或永久保活承诺。

## 本地任务记录

2026-10-29 增补了 [实际后端重复启停验收](RUNTIME_RESTART_TESTS.md)。K30 观察到 PID 不再运行与端口真正释放间存在数十毫秒窗口；退出验收在同一期限内同时检查这两项，并继续验证认证会话恢复及命令不重放。

`files/maintenance/development-sessions.json` 保存两个槽位，分别对应开发环境和桌面。每个槽位只保留当前任务与最近一次中断，记录随机运行 token、宿主进程实例、PID、任务类型、开始时间和最多 8 个终端编号；不保存命令、输出、环境变量、目录内容、凭据或 URL。记录不联网，也不用于恢复旧进程的 PID。

写入采用原子文件、fsync 与 0600 权限，读取有字节/深度/类型边界并拒绝链接和非普通文件。相同进程的活动 token 在内存中登记，完成写入失败后也会解除活动登记，使下一次读取可按中断处理。迟到的旧终端/任务完成不能关闭新的运行 token。

新宿主实例发现未完成任务后，将其转为待恢复状态。Android 11+ 尝试查询本应用该 PID 的 `ApplicationExitInfo`，同时匹配进程名和退出时间；Android 10 或系统没有保留退出记录时，保留“系统未提供退出原因”。不读取崩溃 trace、系统描述或其他 UID 的历史信息。接口依据：[ActivityManager.getHistoricalProcessExitReasons](https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int))。

旧 `runtime-config/session-state` 与 `desktop-state/session-state` 标记只迁移一次，新版不再写入这些简单标记。记录位于整环境恢复替换的 Linux 根目录之外；它属于本机宿主运行信息，不跟随可移植工作站备份迁移。

## 恢复行为

健康服务仍按原有方式连接和重新配对。通知或 OPEN_WORKSPACE 入口在检测到未确认的中断时，停止自动启动，显示原生说明。用户在运行环境点击“重新启动 OpenCode”，或主动使用启动按钮后，才启动服务；桌面始终按需手动启动。后端异常退出也留下可见的失败记录。

重启重新读取官方持久化的会话及项目。旧终端命令、交互输入和运行中的 Shell 进程不能恢复，不自动重放，需要用户重新打开终端。最近一次记录在确认重启后仍保留用于核对。记录损坏时可在服务停止后选择“修复运行记录”：先将损坏文件及原子备份移到私有副本，再重建本机记录，不更改项目、数据库或 Linux 程序。

## 验证边界

K30 Android 16 / ARM64 / 4 KiB，8 项记录层测试在 0.685 秒通过，覆盖中断识别、自动启动拒绝、显式重启、旧 token、双服务、旧标记、元数据隐私、深度/链接/FIFO 拒绝、损坏记录副本、原子备份恢复以及注入磁盘写失败。证据 `.cache/evidence/k30-development-history-fixed.txt`。

真实宿主进程死亡验收 1 项、86.626 秒通过，证据 `.cache/evidence/k30-host-process-death-final.txt`。debug 专用 `LifecycleProbeService` 使用独立 Android 进程和从 APK 新安装的独立 Debian 树，继承实际 RuntimeService；其控制入口只接受测试 UUID，不接受任意根路径。正常用户根目录、home、项目及数据库不挂载到该测试环境，release 不含该组件。实际官方认证会话、双 PTY、gcc 构建、宿主 SIGKILL、自动启动拒绝、显式恢复和不重放命令均通过；项目源码及构建结果保留。9 个采样进程包含宿主及其子进程，全部不再运行（原始 PASS 文本将其统称 descendants，源码输出已澄清包含宿主）。读取失败时另用 signal 0 检查，不能将权限/采样失败直接当作进程退出。

独立进程声明最初使用了系统不接受的进程名，导致 APK 安装失败，随后改用合法的 `:lifecycle_probe`。早期整链路已完成新 Debian 安装、官方服务/会话、双终端及 gcc，但 `/proc` 采样断言失败；这些轮次不计为宿主回收通过。后续按本应用 UID 的实际 PPid 关系及新建进程句柄采集；端口空闲检查与实际启动路径一样启用地址复用，允许 TIME_WAIT，同时拒绝活动监听端口。没有删去对子进程、会话和项目的断言。

以上不替代系统 LMK、电池、长时间后台、完整 Android 10–16、ARM64 16 KiB、平板/外设及实际账号模型的验收。新增原生恢复界面的可见回归仍等待 K30 解锁。
