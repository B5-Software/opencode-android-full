# 项目网页预览

在原生「运行环境」选择「预览项目网页」，输入已启动的开发服务器端口。启动器只检查
127.0.0.1 上该端口是否监听，再打开预览；不启动或改写项目的服务器命令。最近使用的端口
保存在应用私有设置中。例：在项目终端运行 `python3 -m http.server 8000 --bind 127.0.0.1`，
再预览 8000。

预览在单独的 `:preview` 进程中运行，先设置 `project-preview` WebView 数据目录，再初始化
任何 WebKit API。主 OpenCode / 桌面使用原有进程和浏览数据，预览不复制登录 Cookie、
一次性配对链接或原生 Basic 凭据。此方案依据 Android 的
[WebView.setDataDirectorySuffix](https://developer.android.com/reference/android/webkit/WebView#setDataDirectorySuffix(java.lang.String))
多进程数据目录接口。预览自身可以使用 Cookie、DOM storage 和 Service Worker。

初始地址固定为 `http://127.0.0.1:<port>/`，拒绝系统低端口和 OpenCode/VNC/桌面管理端口。
页面内导航限制在所选 origin；用户实际点击的外部 HTTPS 链接交给系统浏览器，自动跳转
不会自动拉起外部应用。普通请求和 Service Worker 的 HTTP 拦截钩子也拒绝管理端口和
file/content URI。预览禁用文件/Content 直接访问、地理位置、自动弹窗和媒体权限；
文件输入可经系统 SAF 明确选择文件。HTTP 认证请求取消，不提供原生账号密码。

支持刷新、页面历史返回、旋转时保存 WebView 导航状态、输入法避让和窗口缩放。
返回主应用不停止 Debian 或现有终端、桌面。渲染器退出时关闭当前预览，不重启后端。

所有项目预览目前共用一个预览 profile；当前隔离范围是预览与主 OpenCode/桌面的浏览身份。
HTTP 钩子有浏览器实现范围，WebSocket 等仍由 WebView 网络栈处理，凭据隔离依赖独立
进程/profile。Linux 开发工具与项目代码仍在同一 Debian 用户空间运行，未增加内核沙箱。

## 当前验证

`PreviewTargetTest` 4 项本地测试通过：端口范围、导航 origin、管理端口和 file/content 规则，
以及项目 API/CDN 正常资源保留。`assembleDebug`、测试 APK、`testDebugUnitTest` 和 `lintDebug` 通过。

K30 / Android16 / ARM64 / 4 KiB：两个真实 WebView 流程通过，分别连接原生 HTTP 夹具和
**Debian/PRoot 中真正运行的 Python 开发服务器**。网页实际执行 JavaScript `fetch`：
请求带有预览自己的 HttpOnly Cookie；没有主 profile 的合成认证 Cookie，没有 Authorization；
预览前后主 profile Cookie 保留，预览 Cookie 没有反向进入主 profile。PID 不同已断言。
没有输出 Cookie 值。测试使用独立 `:preview_validation` 进程和数据目录，不清除真实用户数据。

最新 `.cache/evidence/k30-preview-debian-final.txt` 共 5 项、19.158 秒，通过两个预览流程及
三项实际 C/Python LSP 回归。另一个 `.cache/evidence/k30-preview-lsp-final.txt` 共 5 项、
20.497 秒，通过预览及四项外部 LSP 授权链路。

这些测试在锁屏状态下也能验证构造 WebView 后的网络/脚本行为。启动器按钮的触控、地址栏
视觉、软键盘、旋转和窗口交互仍需 K30 解锁后专项验收，不能由后台链路推定已通过。
Linux 终端可运行 `android-preview 8000`，经原生确认调用同一预览入口。helper 等待确认期间
不连接项目端口，返回前台并分发预览后才成功，取消退出 130，端口未监听退出 1。
协议与真实网页共 6 项、5.453 秒通过（`.cache/evidence/k30-preview-bridge-final.txt`）；
协议测试模拟原生决策，当时真实确认按钮的 UI 分发用例待解锁执行，后续结果见下方补充。预览 WebView 也显式设置
MATCH_PARENT，避免已有主页面曾遇到的 CSS viewport 为零问题；视觉验收仍待解锁。
更多开发服务器/HMR/WebSocket 场景仍需后续专项验收。

2026-10-28 补充：K30 解锁后，“打开预览”真实原生确认按钮已随 25 项容器回归通过，验证批准前不分发、批准后分发独立 PreviewActivity 并完成真实 Linux helper。测试使用 Intent 拦截验证入口，没有把这项当作预览窗口触控/IME 的完整验收。预览原生工具栏现在跟随系统深浅色，并接收本应用渲染模式；页面内容保持原样。
