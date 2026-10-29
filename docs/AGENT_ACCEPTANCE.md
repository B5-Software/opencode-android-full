# 官方 Agent 真机验证

2026-10-28，K30 / Android 16 / ARM64 / 4 KiB。APK 内固定的官方 dev 后端
`2.1.4-dev.f993a89d13c7` 未修改。`AgentWorkflowTest` 首次完整通过耗时 10.036 秒。

测试在应用私有目录建立独立 HOME、XDG 配置/缓存/数据/状态、SQLite 数据库和临时工作区。
服务仅监听随机 loopback 端口，随机认证密码通过 0600 文件传入，清理后删除整个测试命名空间。
不会覆盖用户模型、会话、账号、Git 配置或项目。

`ScriptedModelFixture` 是明确标记的本地 OpenAI Chat/SSE 协议测试服务，没有模型智能，
也不能执行工具或写入项目。它根据真实工具结果决定下一步预设回复。以下操作都由
官方 Agent 循环及其原有 `write`、`shell` 工具在 Debian 中完成：

1. 写入 `calc.h`、`calc.c`、`test.c`、`Makefile`；初始实现有故意设置的减法错误。
2. 执行 `make test`，编译实际 ARM64 ELF，读到真实 `INTENTIONAL_TEST_FAILURE` 后修复源文件。
3. 再次编译并运行测试，确认 `AGENT_TESTS_OK`。
4. 在测试目录提交 Git，并推送到同目录内的本地 bare 仓库；验证两端真实提交摘要相同。
5. 额外两个会话验证真实 shell 权限等待：决定之前文件不存在，`once` 后写入，`reject` 后不写入。
6. 强制结束服务的 PRoot supervisor，重启同一独立状态目录；所有投影消息 ID 保持一致。
7. 继续原会话，模型协议请求仍包含之前回答和工具结果。

证据：`.cache/evidence/k30-agent-workflow.txt`；测试源码位于
`app/src/androidTest/java/io/github/opencodeandroid/runtime/`。
首次启动探针错误地把 `/api/info` 当作 `{data:…}`，造成测试超时；修正为官方顶层
`version` 字段后通过，初次失败记录另存为 `k30-agent-workflow-initial-health.txt`。

这证明实际后端、协议、工具执行、权限和本地持久化链路可运行，**不等于真实云模型质量、
云账号/OAuth、远程 Git 凭据或真实云 API 接入已经验收**。

## MCP 与外部插件

同日 `AgentExtensionsTest` 在同一 K30 完整通过，耗时 4.537 秒。测试插件与 MCP
服务仅放入独立测试命名空间，没有写入用户配置。实际官方 Agent 调用了四个真实扩展工具：

- Debian Python 子进程上的 MCP stdio。
- Debian Python HTTP 服务上的 MCP Streamable HTTP，分别覆盖 JSON 响应和 SSE 响应。
- 独立本地目录中的外部 Promise 插件，覆盖工具注册、执行、`execute.after` hook 和自定义 RPC。

检查真实子进程落盘的调用记录和返回结果，三个 MCP catalog 均为 connected。
外部插件 RPC 读取实际工具注册表；等待工具出现后才提交提示，未靠故意断开模型连接触发重试。
测试协议服务没有错误，证明不是仅看配置或工具名字。

固定 dev 源码的 MCP 使用 StreamableHTTPClientTransport；旧式独立 `/sse` endpoint
握手不在这份实现里。最初测试夹具错误地假定有旧传输回退，失败记录保留在
`k30-agent-extensions-initial-fixture.txt`。改为标准 Streamable HTTP 的 SSE 响应后，
还发现 connected 状态与工具注册表发布之间存在异步间隔；对应记录保留在
`k30-agent-extensions-registry-race.txt`。测试现通过实际注册表等待，不掩盖该上游行为。

源码核查还发现：固定 dev 提供 LSP 配置 schema、GUI 配置列表和事件 schema，
但未找到 LSP 运行模块，write 工具仍标注 LSP 集成 TODO。后续已通过 APK 附带的官方接口
Promise 插件补齐 Linux LSP 运行层，真实 clangd/pylsp 及权限验证见 [LSP.md](LSP.md)。
网络上的真实 MCP OAuth、第三方 npm 插件下载和任意用户扩展兼容性也不由这些本地夹具证明。
