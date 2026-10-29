# Security / 安全报告

The application runs Linux development tools and user-selected code inside Android's application sandbox. PRoot does not provide a separate kernel security boundary. Local services, AndroidBridge authentication, file transfer, updates, backups, and browser separation are security-sensitive areas.

本应用在 Android 应用沙箱中运行 Linux 开发工具及用户选择的代码。PRoot 不提供独立内核安全边界。本地服务、AndroidBridge 认证、文件传输、更新、备份和浏览器隔离均涉及安全边界。

Use this repository's **Security → Report a vulnerability** private reporting entry when enabled. Include affected versions, reproduction steps, impact, and a minimal example. Do not disclose exploitable details, credentials, or private project data in public issues. If private reporting is unavailable, request a private contact without describing the vulnerability publicly.

请通过仓库 **Security → Report a vulnerability** 私密入口报告，说明受影响版本、复现步骤、影响和最小示例。不要在公开 issue 中披露可利用细节、凭据或私人项目。私密入口不可用时，可仅提交请求私密联系方式的 issue。

The project is an engineering prerelease; no stable maintenance branch exists yet. Fixes target main and subsequent prereleases. See [validation](docs/VALIDATION.md) for tested boundaries and remaining security/device acceptance.

项目目前处于工程预发布阶段，尚无稳定维护分支；修复以 main 和后续预发布为目标。已验证范围和待完成验收见 [验证记录](docs/VALIDATION.md)。
