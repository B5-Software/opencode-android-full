# Android WebView 渲染与外观

2026-10-28：只修改 Android Host、资源和诊断工具，没有修改官方 WebUI、终端或桌面的 HTML/CSS/JavaScript。官方前后端仍为同一固定提交的嵌入式可执行文件，APK 检查继续验证其摘要。

## 容器行为

- 官方 WebView 由当前 Activity 持有，最多缓存一个已离开的页面。切换到终端、文件或桌面再回来时，重新绑定回调并复用同一个网页实例，保留 DOM、草稿、历史和滚动位置。
- 页面离开时取消文件选择回调、清除旧界面回调、移出视图树、设为不可见并调用该实例的 `onPause()`；不调用会影响其他 WebView 的全局 `pauseTimers()`。恢复时调用 `onResume()`。
- Activity 销毁或收到内存回收通知时释放闲置实例。官方页面的渲染进程退出也有清理路径；这不等于整个应用已通过系统低内存和长时间压力矩阵。
- MainActivity 处理方向和窗口尺寸变化，横竖屏切换保留 Activity 与 WebView。系统深浅色变化继续由 Android 的资源与 Activity 生命周期处理。
- 默认优先请求 60 Hz，同时设置窗口与官方页面的帧率提示；Android 16 下关闭此模式的触摸升频。运行环境页的“WebView 渲染设置”可改为跟随屏幕。设置只作用于本应用窗口，不修改手机的全局刷新率。
- 项目预览进程通过启动参数接收渲染模式，避免跨进程 SharedPreferences 缓存造成设置过期。
- 使用硬件加速窗口与 WebView 正常合成路径，保持默认图层类型；不额外强制创建整屏硬件纹理，也不预渲染不可见页面。

原设备采样已经显示 **Skia (OpenGL)**，因此不能把这次修改描述为“从软件渲染改成 GPU 渲染”。

## 系统外观与悬浮球

原生界面使用系统深浅色 Material 配色。Android 日间/夜间主题的 `isLightTheme` 同步变化，使 WebView 的 `prefers-color-scheme` 获得相应系统值；不向官方页面注入主题脚本，也不覆盖用户在官方界面内手动选择的主题。

悬浮球菜单提供“不透明度”滑块，范围 20%–100%，默认 90%，修改后保存。仅悬浮球本身参与透明合成，菜单文字和点击区域保持可用。横屏空间不足时，菜单可滚动到滑块。

## 已验证的范围

2026-10-29 增补原生日志订阅隔离：工作区和维护控件只观察运行状态，日志组件单独观察正文；输出合并通知、整行缓冲和长行读取不修改官方 WebUI。实现与边界见 [原生诊断日志](NATIVE_DIAGNOSTICS.md)，当前验收结果见 [验收记录](VALIDATION.md)。这不构成新的网页 FPS 或速度百分比结论。

设备：Redmi K30 5G，Android 16 / API 36，ARM64，4 KiB 内存页。

- `NativeRenderingTest` 验证切页与旋转保留同一 WebView 和真实 JS 文档状态；内存回收后可重建；滑块经 Activity 重建仍保留；日间/夜间主题下 WebView 媒体查询实际分别返回 false/true；窗口帧率设置可以切换。
- 25 项组合回归用时 66.530 秒，包括上述 4 项，以及全屏/上传/返回、自适应布局、真实多面板桌面、文件操作、8 项 AndroidBridge UI、过期 Cookie 和已消费配对链接后的浏览器 API=200。
- 原生文件夹→官方会话及路由→实际 PTY 工作目录→未保存编辑保护→实际 Thunar 初始与切换项目窗口，另 2 项、17.680 秒通过。目录含中文、空格及 shell 特殊字符，已有终端不改目录。
- 窗口帧回调专项采样：优先 60 Hz 时约 60 次/秒，跟随系统的首秒约 76 次。该数据验证窗口调度生效，不是网页滚动 FPS、CPU 降幅或 INP 的对比结论。
- 更新预览进程的模式传递后，4 项渲染/外观测试和 2 项真实预览隔离测试合计 6 项、13.147 秒通过；后者包含实际 Debian Python 项目服务器。可重复运行的文档保留测试直接加载自有合成文档，避免已安装的官方 service worker 截获 HTTP 夹具导航，不清理真实用户或验证 profile。

证据保存在 `.cache/evidence/k30-native-render-final-regression.txt`、`k30-project-directory-ui-final.txt`、`k30-native-frame-cadence.txt`。本地界面截图使用应用自身捕获，不读取其他应用界面。

## 采样方法与限制

早先一个加载阶段的 6 秒样本出现 719 次样式重算、约 0.7795 秒样式计算；之后页面稳定时，6 秒重算为 0、任务耗时约 0.0329 秒。两次 DOM 数量不同，不能把它们当成同一场景，也不能将加载时开销归为持续卡顿的唯一原因。

可重复采集当前官方页面的数字指标：

```powershell
python -m pip install websocket-client
python tools/profile_webview.py --serial <serial> --output .cache/evidence/webview-sample.json
```

工具只针对此应用的 debug 主进程，临时建立并移除专用 ADB 转发。它读取页面尺寸、节点/动画数量和 Chromium Performance 计数，同时保存该应用的 gfxinfo；不导出 Cookie、页面文字、完整 URL 或模型凭据。采样期间需保持同一页面可见；`gfxinfo` 会重置该应用的累计性能计数。

追加 `--check-connection` 可读取浏览器自身 Cookie 请求的 API 状态码。最终闲置样本的 gfxinfo 总帧数为 0，此时系统输出的 4950 ms 分位数是空样本占位值，不能当作实际延迟或卡顿。

当前没有足够一致的交互场景，不能给出“整体快了百分之多少”的结论。真实长对话、流式输出、软键盘和复杂页面仍需持续交互采样。

最终恢复正常用户 profile 后，页面自己的 Cookie API=200；6 秒可见页面样本尺寸为 392×872 CSS 像素、DOM 956 个节点、运行中动画 0、样式重算 0、任务耗时约 0.1197 秒、JS 堆约 13.11 MiB。该场景与修改前的 DOM 数量不同，因此不计算前后速度或内存改善比例。记录为 `.cache/evidence/k30-webview-perf-after.json`。

独立匿名请求仍返回 401，记录为 `.cache/evidence/k30-native-render-normal-auth.json`。专用诊断转发均已移除，设备留在正常 OpenCode 工作区，后端运行中。

本地交付 APK 为 `artifacts/opencode-android-full-arm64-render-debug.apk`，SHA-256：`8ff4f83b82aadef4f4ad3425d2a1ba02b9b28148c71d76dc27b9b9f5df217927`。它是已安装 K30 的工程调试包，完整 M0–M5 与签名发布验收仍在进行。
