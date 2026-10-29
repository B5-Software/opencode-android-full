# 官方前端的本地连接恢复

2026-10-28 用户报告前端没有自动连接。对 K30 正在运行的本应用 WebView 进行只读诊断：
页面位于 `http://127.0.0.1:49374/`，本地会话 Cookie 存在且为 HttpOnly，但页面自己的
`fetch('/api/info', {credentials:'include'})` 返回 **401**。诊断输出仅保留 Cookie 元数据，
没有输出或保存凭据值。

旧代码把“Cookie 名称存在”当作认证有效，跳过官方一次性配对链接；恢复 WebView 历史时
也没有验证浏览器身份。Cookie 签名失效后，页面仍能显示，API 却无法连接。

修复后的流程：

1. Android 仅携带浏览器自己的 Cookie 请求 `/api/info`，不会用 HTTP Basic 隐藏浏览器认证失败。
2. 认证失效时，由 Native HTTP 客户端消费官方的一次性配对链接，验证 302、目标路径和
   HttpOnly Set-Cookie，再写入 WebView CookieManager。这样旧 service worker 不会截获配对导航，
   一次性链接也不进入页面 JavaScript、历史或公开日志。
3. 链接已使用或过期时，请运行中的 RuntimeService 生成新链接；不重启后端、PTY 或桌面。
4. Cookie 认证验证为 200 后才恢复官方页面/历史。可信本地 API 后续出现 401 时，先复查
   `/api/info`，只在浏览器身份确实失效时重新配对，不干扰模型提供商自身的登录失败。

RuntimeService 的重新配对操作只作用于自身仍存活的进程。服务停止会正常 stopSelf，
取消后的异步清理期间不接受第二个运行时所有者。通知的“返回 OpenCode”使用显式
`OPEN_WORKSPACE` Activity action，可打开工作区并在需要时启动已安装环境。
从运行环境启动服务后，会自动进入 OpenCode 页面。

`OpenCodeAcceptanceTest` 现在验证页面实际 Cookie API 状态，不能再只用 root 高度大于 100
证明“已连接”。隔离 WebView profile 内注入失效 HttpOnly Cookie，并将一次性链接提前消费，
确认自动恢复后页面 API=200，匿名 API 仍=401。单项 4.380 秒通过；与全屏、旋转、返回和
真实文件字节上传合计 6 项 17.350 秒通过。

证据：`.cache/evidence/k30-webui-reconnect.txt`、
`.cache/evidence/k30-connect-fullscreen-regression.txt`。
测试没有清理或修改用户真实 WebView 的 localStorage、会话历史或项目。

最后使用正常用户 profile 冷启动并打开工作区，现场复查页面自己的 API=200、root 高度873，
HttpOnly 会话 Cookie 保留。只读 DevTools 转发已移除。记录位于
`.cache/evidence/k30-live-webui-connection.jsonl`。手机现留在正常 OpenCode 工作区，后端运行中。
