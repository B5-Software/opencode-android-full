# Linux LSP 插件

固定的官方 dev `2.1.4-dev.f993a89d13c7` 提供 LSP 配置和 GUI 状态 schema，运行模块尚未实现。
本项目通过官方 Promise 插件接口加载 APK 附带的 `opencode-android-lsp`，补齐 Linux 侧
LSP 客户端、工具及写入后诊断。官方 Agent、WebUI 和服务端可执行文件保持原样。
Android Host 只安装签名 APK 内的插件源码，并管理 Linux 包安装任务。

## 使用

在「运行环境」停止 OpenCode 和桌面，点击「安装 / 更新语言服务」。通过 Debian 签名源
安装 clangd、pylsp、pyflakes 和 pycodestyle，保留现有 apt 源、项目和其他已安装软件。
默认使用中科大镜像；服务 action 可通过 `mirror=official` 选择 Debian 官方源。
安装日志和失败状态在运行环境页面显示，可停止；下一次安装先运行 `dpkg --configure -a`。
语言服务包为按需安装，基础开发镜像仍是原有 developer-v1，不能把它写成已内置这些包。

启动 OpenCode 后，插件从上游的全局 `plugins` 目录自动加载。模型工具 `lsp` 提供：

- `status`、`diagnostics`。
- `goToDefinition`、`findReferences`、`hover`。
- `documentSymbol`、`workspaceSymbol`、`completion`。
- `goToImplementation`、`goToTypeDefinition`。
- `prepareCallHierarchy`、`incomingCalls`、`outgoingCalls`。

除 status 外传入源文件 `filePath`，位置参数 `line` 和 `character` 从 1 开始，
character 使用 UTF-16 单位。workspaceSymbol 另传 `query`。服务器未声明的能力明确返回
unsupported。插件不替模型执行重命名或服务器发来的任意文件编辑。

`write`、`edit` 成功后附带当前语言服务诊断。服务器缺失/崩溃或读取被拒绝时，已成功的保存
不被改成失败，metadata 标记 unavailable。显式 LSP 查询仍返回实际错误。
查询与自动诊断都遵守上游权限：项目目录或同一 worktree 内使用 location 相对路径检查 read；
项目外文件先审批 external_directory，再检查绝对路径 read。目录审批的保存范围使用最近的
.git/.hg 边界，否则使用文件所在目录。符号链接同时检查别名和真实目标，审批期间改指向会
使原授权失效。两步审批完成前不启动语言服务器或读取源文件内容；实际读取拒绝非普通文件、
最终路径符号链接、超过 2 MiB、NUL 或无效 UTF-8。

## 配置与生命周期

支持普通 `opencode.json` / `opencode.jsonc` 的 `lsp` 配置、全局与项目层、显式配置文件、
inline 配置以及原始配置文本中的 `{env:NAME}` / `{file:path}`。文件相对配置文件目录解析，
inline 则相对项目目录；文件内容 trim 后按 JSON 字符串转义，缺失/不可读文件替换为空。
环境先替换，文件内容不递归展开；行注释中的文件宏保留。可设置 command、extensions、env、initialization、disabled，
或通过 `lsp: false` 停用。插件选项 `servers` 也可提供覆盖，`disabled` 可整体停用。
配置顺序为全局、显式文件、外到内的项目文件、外到内的 .opencode 目录、inline、Linux 的
/etc/opencode，最后应用插件选项。同目录 jsonc 覆盖 json。相对 executable 以项目为基准，
无路径命令按实际子进程环境的 PATH 查找。远程配置来源和完整上游配置合并矩阵尚未全部验收。

默认发现已安装的 clangd、pylsp、typescript-language-server、rust-analyzer、gopls。
不会自动下载或执行远程语言服务。其他 LSP 服务器可通过配置接入；不能据这些默认项推断所有
语言服务器都经过真机兼容验证。

每项目最多 4 个按需启动的服务器，120 秒空闲后释放。支持请求超时、取消、增量文档版本、
push/pull diagnostics、初始化/关闭和异常退出清理。未改变的文件保留版本及对应诊断，避免
要求服务器为 no-op didChange 重复发布相同结果。插件 reload 时关闭子进程，整个 supervisor
被终止时，由 PRoot EXITKILL 回收 tracee。

插件为 Linux 开发工具，不提供新的内核隔离边界。语言服务器与其他项目开发工具在同一
Debian 用户空间运行。它没有 Android 系统权限桥，也不向普通 WebView 页面暴露方法。

安装目录：`/opencode-state/config/opencode/plugins/opencode-android-lsp`，只更新带有本应用
managed marker 的文件，拒绝目录、目标文件和 AtomicFile sidecar 的符号链接，不覆盖同名
用户插件。可用上游 plugins 的移除指令停用该绝对路径。未来上游出现自身的 `lsp` 工具时，
插件优先保留上游工具。

协议与 clangd 配置参考：[Microsoft LSP](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)、
[clangd 官方文档](https://clangd.llvm.org/installation)。

## 已验证范围

K30 / Android 16 / ARM64 / 4 KiB：签名 apt 安装 clangd 19.1.7、pylsp 1.12.0 通过。
真实官方 Agent + 本地脚本模型协议夹具验证 C/C++ 自动语义诊断、错误修复后诊断清空、
定义、引用、hover、documentSymbol 与 supervisor 退出后的 clangd 回收，另有 managed 安装
与符号链接保护两项验证，合计 3 项 5.821 秒。证据 `.cache/evidence/k30-agent-lsp.txt`。

后续追加 pyflakes 3.2.0、pycodestyle 2.12.1（签名 apt 安装 16.028 秒）。中文文件名 Python
自动语义诊断、修复清空、定义/hover，以及相对 read deny 同时阻止显式 LSP/自动反馈通过。
其中授权 write 保持成功，拒绝的读取没有启动语言服务器。包含安装保护共 5 项 19.498 秒通过。

另有 `node --test tools/test_lsp.mjs` 12 项验证：分片 UTF-8、旧版本诊断拒绝、no-op 版本、
取消/超时监听器清理、超大帧、pull diagnostics 的无效 unchanged、JSONC、外部审批顺序、
同 worktree 读取、符号链接改指向、配置插值/顺序和相对命令。
关闭连接现等待实际子进程 close，Unix 服务器拥有独立进程组，并清理派生 worker。
K30 实际 clangd PGID=PID 及 supervisor 回收也已验证。协议夹具只保留在工作区 `.cache/lsp-unit`。

追加 K30 外部授权 4 项 17.569 秒通过：目录与读取分步审批后实际 clangd 诊断、目录拒绝、
链接目标 read deny、审批期间链接改指向。证据 `.cache/evidence/k30-lsp-external-final.txt`。
Agent/LSP/MCP/项目目录整组回归 8 项 39.515 秒通过，证据
`.cache/evidence/k30-lsp-access-regression.txt`。Linux 下 12 项本地测试全过；Windows 下 11 项通过，
符号链接单项交给 Linux 验证。其他查询操作、其他语言服务器和其他设备
仍不能由这些测试推定通过。本地模型夹具只返回预设协议响应，分析由实际语言服务器完成，
不代表云模型验收。
