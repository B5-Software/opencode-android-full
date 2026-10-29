# 文件和桌面

“文件”页直接管理独立保存的 `/workspace`，无需启动 OpenCode。支持浏览/搜索当前目录、新建文件或目录、UTF-8 文本编辑、重命名、SAF 多文件导入和单文件导出。重名导入自动另存，保存前比较内容摘要，避免覆盖其他工具的修改；编辑器限制 2 MB。返回键先处理未保存内容及目录返回，最后才询问退出应用。

删除操作移至应用私有 `backups/project-trash/`，保留原路径并支持恢复；同名目标或已消失的原父目录会阻止恢复。路径分量校验、逐级符号链接检查和读文件 NOFOLLOW 阻止文件管理器意外访问工作目录之外。PRoot 和这些路径检查都不构成针对同一 Android UID 中恶意原生代码的内核隔离。

文件菜单提供复制、移动到其他目录及系统分享。复制会先暂存，支持进度和取消，再完整切换；移动避免覆盖同名项。分享提供独立只读副本，修改原文件不会改变已生成的副本，接收应用无法写入副本或访问整个项目。Debian helper 的文件导入导出和分享说明见 [AndroidBridge](ANDROID_BRIDGE.md)。

桌面在独立 `DesktopService` 中按需运行，不随 OpenCode 自动启动。首次安装可选择 Debian 官方或中科大 HTTPS 镜像；通过独立的 apt sources/list 目录工作，不替换用户的默认软件源，不关闭签名校验。安装 XFCE、TigerVNC、noVNC、Chromium、中文字体及基础桌面程序，安装中断后下一次先运行 dpkg 配置恢复。

桌面进程使用 PRoot 的 developer/1000 内部身份，宿主仍是普通 Android 应用 UID。XDG 配置和浏览器配置位于独立 desktop-state，项目仍共享 `/workspace`。VNC 监听本机 5901、网关监听本机 6080；网关 HTTP Basic 密码在 Android Keystore 加密保存，WebSocket 使用独立派生凭据并验证 Origin。桌面不使用跨端口共享的认证 Cookie，密码不放入 URL、进程参数或环境变量。网关的凭据文件为私有 0600，在停止后删除。

桌面页的“浏览器”按钮可打开 Chromium 模式选择。标准模式默认保留 Linux 沙箱；当前 K30 的标准 Chromium 启动退出 133，保留了失败证据。兼容模式须由用户明确启用，使用 `--no-sandbox` 和独立 profile；它仍可访问本应用文件，重要账号应交给 Android 系统浏览器。兼容测试只访问专用本地页面，没有更改用户默认模式。完整 Linux 内核沙箱及其他设备的兼容性仍需评估。

桌面切换页面后继续运行，返回后重新认证连接；可在运行环境或通知中停止。正常停止先通知会话退出，超时再杀进程；启动时只清理私有临时目录中、属于当前 UID 且关联进程已不存在的 X11 运行节点。更新 rootfs 与桌面启动通过原子状态切换互斥。

回归类：`FullscreenWorkspaceTest`、`ProjectFilesTest`、`ProjectFilesUiTest`、`DesktopInstallTest`、`DesktopAcceptanceTest`、`ChromiumSandboxTest`。具体通过/失败记录见 [VALIDATION.md](VALIDATION.md)。
