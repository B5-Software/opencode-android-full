# AndroidBridge

项目预览命令 `android-preview PORT` 已接入同一认证 IPC。审批前不探测项目端口；用户点击
“打开预览”后才检查选定的 127.0.0.1 端口。检查完成仍须等主 Activity RESUMED 后分发
独立 PreviewActivity，分发成功才向 helper 返回规范 URL。关闭端口返回退出码 1 及可操作
错误，取消返回 130。拒绝 OpenCode/桌面/VNC 管理端口、低端口、非整数及任意 URL 参数。
浏览数据隔离范围见 [PROJECT_PREVIEW.md](PROJECT_PREVIEW.md)。

K30 实际 helper/IPC 与浏览器共 6 项、5.453 秒通过：审批前无 TCP 连接、取消、准备后等待
分发、一次性消费、关闭端口及非法 payload，以及真实 Debian Python 服务的 Cookie 隔离。
原生决策在协议测试中由测试模拟；真实点击确认到 Activity 分发的 UI 用例已补写，仍待解锁
执行，不能把协议通过算作该 UI 通过。证据 `.cache/evidence/k30-preview-bridge-final.txt`。

AndroidBridge 由 RuntimeService 和 DesktopService 共享持有，最后一个开发会话停止时关闭。它使用随机名称的 Android 抽象 Unix socket，校验内核提供的对端 Android UID及每次启动新生成的 256 位令牌；没有 HTTP 监听端口，也没有向普通 WebView 暴露系统 JavaScript API。

APK 提供 Python helper 和小型 shell 入口，放在 `/android-runtime/bin`，通过现有 Debian Loader 运行。`BROWSER` 默认指向 `android-open`，PATH 优先使用该目录的 `xdg-open`。这些脚本随签名 APK 更新；不会下载远程桥接程序。Debian 中访问：

| 命令 | Android 操作 |
| --- | --- |
| `android-open https://example.com` / `xdg-open URL` | 请求通过 Android 打开 HTTP(S) 链接 |
| `android-preview 5173` | 经原生确认检查该本机端口，在独立预览中打开，成功输出规范 URL |
| `android-clipboard get` | 经用户允许读取纯文字剪贴板 |
| `android-clipboard set TEXT` / `printf text \| android-clipboard set` | 经用户确认写入纯文字剪贴板，标记敏感内容以隐藏系统预览 |
| `android-notify TITLE TEXT` | 经用户确认发送本地通知，尊重通知权限 |
| `android-share /workspace/file` | 选择接收应用，提供该文件的只读副本 |
| `android-import /workspace/project` | 系统文件选择器，导入选中文件；输出实际 Linux 路径 |
| `android-export /workspace/file` | 系统保存位置选择器，导出文件 |

`gh auth login --web` 继承 BROWSER，可将其浏览器打开请求交给 Android；GitHub 的设备码和登录由官方 gh / Android 浏览器继续处理。测试没有登录用户的真实 GitHub 账号。Linux 文件的普通 `xdg-open` 仍交给 Debian 原有处理器。

请求出现在原生确认界面；Activity 不在前台时保留请求，并在已有通知权限时显示提示。桥接服务不会直接从后台拉起浏览器。用户取消返回退出码 130，环境停止、无效请求或超时返回失败。文件选择、分享和剪贴板操作均不接受任意 Android Intent 或任意宿主路径。文件传输限 `/workspace`，继续使用文件仓库的路径与符号链接检查。HTTP(S) 链接拒绝用户名/密码字段、控制字符和其他 scheme；确认界面不显示 URL 查询或片段中的潜在令牌。

协议为一行 UTF-8 JSON：version=1、token、action、payload；响应含 status 和 value/error。单帧最多 512 KiB、剪贴板最多 64 KiB、同时最多 4 个等待连接，每个请求最多等待 600 秒。连接断开会撤销尚未完成的请求。临时认证文件位于私有 runtime-config，权限 0600，最后一个持有者退出时删除。身份令牌、剪贴板和 OAuth URL 不进入运行日志。

文件页的复制与移动也已接入：复制在私有暂存目录完成后才切换到目标，支持取消及进度；移动通过同一卷内重命名完成。复制保留普通文件权限和链接对象，不跟随链接。重名时保留原扩展名并另存。分享仅暴露 cache/shared-files 下的选中文件副本，接收应用只有 URI 读取权限；下一次分享清理超过一天的旧副本。

PRoot 不是内核隔离边界，同一 Android UID 内的原生代码不受这些目录检查的对抗性隔离。系统操作额外保留原生用户确认；API 不提供任意命令执行或全应用文件访问。

验证见 `AndroidBridgeTest`、`ProjectFilesTest`、`FileSharingTest`、`ProjectFilesUiTest` 及 [VALIDATION.md](VALIDATION.md)。所用 IPC API：[LocalSocket](https://developer.android.com/reference/android/net/LocalSocket)、[LocalServerSocket](https://developer.android.com/reference/android/net/LocalServerSocket)。
