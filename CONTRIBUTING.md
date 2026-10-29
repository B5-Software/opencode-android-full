# Contributing / 参与贡献

[English README](README.md) · [中文 README](README.zh-CN.md)

Report the Android version, ABI, page size if known, application version, and reproduction steps. Include only redacted diagnostics. Do not attach model keys, GitHub tokens, private projects, backup passwords, signing keys, or device identifiers.

提交问题时请说明 Android 版本、ABI、已知的内存页大小、应用版本和复现步骤。诊断记录请先脱敏，不要上传模型密钥、GitHub token、私人项目、备份密码、签名密钥或设备唯一标识。

Keep the official OpenCode frontend intact. Android input, rendering, lifecycle, and platform integration belong in the host. Retain API 36 targeting, API 29 minimum, ARM64 support, and APK-native-library execution. Do not require Android root or an external Termux installation.

保留官方 OpenCode 前端。Android 输入、渲染、生命周期和平台集成在宿主实现；保留 targetSdk 36、minSdk 29、ARM64 和 APK 原生库加载方案，不引入 Android root 或外部 Termux 依赖。

Explain the concrete behavior change and validation in pull requests. Use the [build recipe](docs/CI.md); run relevant Python/Node tests for their tools and Gradle unit tests/lint for Android changes. Identify hardware used for device claims. Static ELF alignment is not an ARM64 16 KiB device test.

PR 请说明行为变化与对应验证。脚本变更运行相关测试，Android 变更运行适当的单元测试和 lint。真机结论要注明平台；ELF 静态对齐检查不能代替 ARM64 16 KiB 真机测试。

Translations must preserve placeholders, paths, product names, and fallback behavior. Check them with `python -m unittest discover -s tools -p test_native_i18n.py`. Record upstream pins, license texts, and build instructions when adding distributable components.

Contributions are provided under GPL-3.0-or-later, with third-party material clearly attributed under its applicable license.
