# 默认遥测配置与本地认证传输

本轮检查针对锁定的完整官方 OpenCode `f993a89d13c73ff6f6537d6b97e78e138a3ea363`，以及 Android Host 的默认启动、构建和本地认证。官方前端源码/资产未修改。

## 当前版本的默认导出条件

- 官方 `packages/app/src/entry.tsx` 仅在 `VITE_SENTRY_DSN` 非空时初始化 Sentry。Android 构建脚本将该变量明确置空，锁文件要求 `sentryDsn=unset`。SDK 仍属于完整官方程序，不通过删改前端移除功能。
- 官方 CLI 从环境读取 `OTEL_EXPORTER_OTLP_ENDPOINT`；`packages/util/src/observability/otlp.ts` 在 endpoint 不存在/为空时返回空远程日志列表及空 tracing layer。用锁定 Bun 运行这些默认分支成功；上游自带的正向测试也成功将 span 发送到显式配置的本地测试 collector。
- `ProotCommand` 使用明确宿主环境及 Debian `/usr/bin/env -i`，设置 `DO_NOT_TRACK=1`，没有默认配置 OTLP 导出地址。真实 Debian 验收将合成 OTLP/Sentry/提供商变量放入 PRoot 宿主环境，再检查来宾中这些变量均未继承；不读取或打印用户的实际环境值。

源码审阅路径和 SHA-256 记录在 `.cache/evidence/privacy-source-audit.json`。这些证据限定于上述版本和默认配置，不等价于所有依赖、用户插件、手动安装软件或未来更新的完整网络审计。用户配置的模型、MCP、包下载及其他主动网络操作保留原有功能。

## 构建机配置隔离

`tools/build_opencode.py` 为上游依赖安装和构建创建独立环境副本，移除继承的 `SENTRY_*`、`VITE_SENTRY_*`、`OTEL_*`，随后设置空 `VITE_SENTRY_DSN` 和锁定版本/平台/时间戳；也清理上游测试页面与外部快照覆盖变量。这样构建机已有的 Sentry 上传 token、组织/项目不会意外启用上游 sourcemap 上传插件。包下载所需的代理、PATH 等环境保留。该行为已加入现有 CI 测试入口 `tools/test_ci.py`。

## 本地认证显式直连

`LoopbackHttp` 仅接受明确的 `http://127.0.0.1:<1024–65535>` 地址，拒绝 userinfo 和 fragment，明确使用 `Proxy.NO_PROXY` 并关闭自动重定向。OpenCode Basic API、一次性浏览器配对/Cookie 检查、更新健康探测及桌面网关原生健康检查使用此路径。

这遵循 [Android URL.openConnection(Proxy)](https://developer.android.com/reference/java/net/URL#openConnection(java.net.Proxy)) 的显式直连语义，避免原生本地凭据/项目路径经默认 ProxySelector 路由。远程 HTTPS 更新和用户网络配置沿用各自通道；此改动限定于原生 HTTP 客户端，不是 VPN 或整个 WebView 网络策略的替代品。

`LoopbackHttpTest` 在插桩进程内安装临时 ProxySelector，先用不含秘密的对照请求证明代理陷阱可工作，再验证实际 Basic/匿名检查、一次性配对、Cookie 和重定向拒绝路径。测试只使用合成凭据及回环服务器，finally 恢复该 Java 进程的 selector，不修改 Android 系统代理配置。

实际测试结果和制品见 [验收记录](VALIDATION.md)。本轮不提供网页 FPS、全版本 Android、LMK 或所有软件零外连的结论。
