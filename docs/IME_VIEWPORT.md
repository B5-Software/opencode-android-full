# 输入法动画与 WebView 视口

Android 容器原先使用 Compose `imePadding()`。它会随着输入法动画每一帧调整页面高度；对完整 OpenCode 页面，这会反复触发 Chromium 视口变化和页面重排。

现在由 `StableImeViewport.kt` 使用 [`WindowInsets.imeAnimationTarget`](https://developer.android.com/reference/kotlin/androidx/compose/foundation/layout/WindowInsets.Companion) 预留动画结束后的输入法高度。工作区和项目预览使用同一处理方式。输入法本身继续由 Android 动画显示与隐藏，网页无需为每个中间高度重新布局。窗口旋转、分屏、输入法高度变化仍按新的目标尺寸布局。没有修改官方前端 JS/CSS、关闭系统动画、强制软件渲染或重新加载网页。

K30 / Android 16 / 实际系统输入法，使用正式 `AdaptiveWorkspace` 容器和独立的 1800 行合成网页测试：

| 回合 | 修改前展开 / 收起的视口高度变化次数 | 修改后 |
| --- | --- | --- |
| 1 | 18 / 19 | 1 / 1 |
| 2 | 21 / 21 | 1 / 1 |
| 3 | 26 / 22 | 1 / 1 |
| 4 | 27 / 23 | 1 / 1 |

输入法展开后的 WebView 高度均为 1567px，收起后均为 2400px。修改前测试耗时 9.915s，修改后 9.805s；该耗时包含固定等待，不能用来计算页面流畅度提升比例。这项验收证明消除了动画中反复改变 Chromium 视口的开销，并不等于所有机型或真实项目都达到固定帧率。

证据：`.cache/evidence/k30-ime-before.txt`、`.cache/evidence/k30-ime-after.txt`。测试只使用本应用窗口、合成网页和数字尺寸，没有采集用户输入、会话内容或全局设备截图。
