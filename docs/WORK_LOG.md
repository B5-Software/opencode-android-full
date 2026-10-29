# 当前工作交接

## 最新：Linux受控预览helper与模型配置元数据核查（2026-10-28）

完整goal已重新全文读取；上轮为progress，本轮也有代码和K30证据，goal保持active，不标complete/blocked。没有子代理授权，未使用子代理。K30开始及结束仍Dozing/mStayOn=true，原解锁问题未回复；没有重复请求或绕过锁屏。

新增实际Debian命令android-preview PORT：APK helper安装表/Python协议/AndroidBridge validate和原生确认界面接通。共享PreviewTarget.isListening只在原生审批后检查选定127.0.0.1端口，先前无TCP接触；JSON port只接受有限整数Number，拒绝字符串/小数/布尔/null、低端口和49374/6080/5901，helper也先校验参数，不接收任意URL。准备成功置BridgeRequest.previewPort；主界面repeatOnLifecycle RESUMED后一次性consume并分发独立PreviewActivity，分发后才complete并向helper输出规范URL。后台/未分发时helper继续等；取消130，闭端口1并提示先启动服务。无新HTTP/JS桥接接口，不复制任何凭据。

PreviewActivity补显式MATCH_PARENT及深色背景，沿用主页面已知CSS viewport修复；debug进程启用自身WebView调试供将来验收。PreviewTarget JVM策略现在5项通过。assembleDebug/AndroidTest、testDebugUnitTest、lintDebug通过。PreviewBridgeTest4项真实Linux helper/认证IPC+PreviewIsolationTest2项(含真正Debian Python服务/JS请求/Cookie双向隔离)，6项5.453秒全过，证据.cache/evidence/k30-preview-bridge-final.txt。首轮一项测试把LocalSocket.setSoTimeout放在connect之前而失败，已修正顺序，余测试及重跑均通过。协议测试的原生决策由测试模拟；AndroidBridgeTest新增真实点击确认/Intent分发UI用例，仍锁屏未执行，不能算UI通过。原有其余Bridge UI回归也待解锁再跑。

为真实模型验收先核查正常用户状态：ModelMetadataSnapshot以Android SQLite OPEN_READONLY只SELECT凭据条数，不SELECT value；临时Node复用APK JSONC parser仅输出配置计数，未输出名称/端点/模型ID/密钥，临时目录已清理。k30-model-metadata.txt结果：normal_database_exists=true，credential_rows=0，active=0，config_files=0，parse_errors/skipped=0，provider_override_entries/raw_api_key_fields/default_model_fields均0。此为诊断，不是推理或连接验收。已新发异步模型选择问题(云API请用户在官方OpenCode中配置，不要在聊天发密钥；或回复本地服务地址+模型名)，还未回复。独立模块继续做，不因此阻塞整个goal。

当前K30开发APP653518226字节SHA256 187c87b4edba29379a2fd741f55713f51351e1b5e49ed0c4bd431c665453bc08；测试3136143字节SHA256 3ae99485e56604eb47ca2df4907dc5aa4f871eca356d3c119ec17ea2741f54cb。见preview-bridge-build-hashes.json。preview-bridge-apk-check.txt再次通过官方dev摘要/min29/target36/宿主与APK16K静态对齐；真ARM64 16K仍缺，不能据静态检查声称通过。artifacts仍是原稳定M4版61ac0375.../9c31034d...，未覆盖。

所有命令/测试均终止，**无活跃exec/test句柄**。最后force-stop清测试进程后正常OPEN_WORKSPACE启动用户后端；匿名/api/info=401，认证强制保留，见k30-preview-bridge-normal-backend.json；本轮未得到新的正常前端自身API200/UI证据。forward19374已移除，未动其他任务旧forward；桌面停止按需，用户项目/真实Cookie/模型配置均未清。文档ANDROID_BRIDGE/PROJECT_PREVIEW/VALIDATION已同步。

下一步：解锁后优先目录入口组合UI、预览启动器/Bridge确认/旋转/返回/视觉/原有界面回归；模型配置好后做真正模型多文件工程，继续真实GitHub登录与推送。独立工作有预览Node/HMR/WebSocket/ServiceWorker真实协议验证、完整备份恢复、LSP剩余配置/能力覆盖、后台/压力/电池和安全许可证。真实ARM64 16K、Android10–16矩阵、实平板/外接屏/外设和签名发布未完成，全部完整目标保留。无remote/提交/发布；历史拒绝的Windows Temp删除/Docker/全局ADB UI组合动作不重放，本轮无新增自动审批拒绝。

## 最新：LSP外部授权/配置、独立项目网页预览已在K30验证（2026-10-28）

完整v2.0 goal仍active，本轮为实际progress，不标complete/blocked。已读取指定pasted-text-1与goal内容，未使用子代理。K30仍Dozing，先前解锁异步问题沿用，未重复发问、未绕过锁屏。屏幕超时2147483647、USB常亮15、mStayOn=true保留。

LSP新增access.mjs并同步BundledPlugins第五个安装文件。按上游FileAccess语义支持项目/worktree外文件：external_directory先于read，目录保存范围取最近.git/.hg边界；同时检查别名和真实路径，WeakSet授权令牌及审批/查询/读取前校验防链接改指向。读取NOFOLLOW/NONBLOCK，普通UTF8文本2MiB限制。config补齐env→file插值、相对配置目录/inline项目路径、trim/JSON转义、缺失空值、行注释保留、无递归展开；修正全局/explicit/项目/.opencode/inline/Linux系统层次和jsonc覆盖，executable相对项目及实际子进程PATH。详细范围见LSP.md；完整上游配置合并/远程来源矩阵尚未全覆盖。

ExternalLanguageServerTest四项实际Agent+clangd审批/拒绝/目标read deny/审批期间改指向通过。首轮测试解析无content消息失败已改optJSONArray，后四项17.569秒通过(k30-lsp-external-final.txt)。BundledPlugins2/LSP3/AgentWorkflow1/MCP1/目录1，8项39.515秒通过(k30-lsp-access-regression.txt)。模型为本地脚本协议夹具，实际语义分析为clangd/pylsp，不是云模型验收。

新增preview目录：运行环境“预览项目网页”输入端口并检查TCP，固定http127.0.0.1，拒绝低端口和49374/6080/5901。独立:preview进程先设置project-preview数据目录，不复制主Cookie/Basic/配对链接，无JS系统桥。导航限所选origin，用户点击外部HTTPS才开系统浏览器；HTTP/ServiceWorker钩子拒绝管理端口及file/content。自身Cookie/DOMstorage/Worker保留，SAF文件输入、返回/刷新/历史/旋转导航状态/IME代码已加。全部项目共用预览profile；HTTP钩子不是网络防火墙，不声称逐项目/内核隔离。AndroidBridge的预览helper尚未接入。详见PROJECT_PREVIEW.md。

debug :preview_validation及独立profile保护真实用户数据。PreviewIsolationTest实际网页JS fetch验证不同PID、自身HttpOnly cookie正常、无主Cookie/Authorization泄漏、反向无污染且主值保留。最初测试的匿名ResultReceiver跨进程类不在目标APK导致崩溃，已在测试端Parcel roundtrip为框架ResultReceiver、仅传Binder，修复后通过；生产预览无测试回调。新增真正Debian/PRoot Python HTTP服务而非仅原生夹具，同样通过并退出/清理自身UUID目录。没有输出Cookie值。

最新安装APK证据：k30-preview-lsp-final.txt 5项20.497秒(预览+外部LSP4)；k30-preview-debian-final.txt 5项19.158秒(原生HTTP预览、真实Debian Python预览、C/Python LSP3)。Node12项WSL Linux全过(含链接改指向)，Windows11过/链接skip；PreviewTargetTest4项过。assembleDebug/AndroidTest、testDebugUnitTest、lintDebug通过；preview-apk-check.txt确认官方dev摘要、min29/target36、宿主16K静态对齐，仍不等于真ARM64 16K。

当前K30及build outputs开发APK：APP653518226字节SHA256 32d50cfac5b26ea3f39727fe5491a807d15ad416b12ae3bc3446c1db0e5d431a；测试3126187字节SHA256 8c8337565e881826e8f34f406825b7374ee75da98f87945000baea20a6ac0376。见.cache/evidence/preview-build-hashes.json。artifacts仍保留稳定M4版61ac0375.../9c31034d...，未用UI待验收版本覆盖。

**无活跃exec/test句柄。** 最后force-stop本应用清理测试，正常OPEN_WORKSPACE启动用户profile后端；匿名API401，认证继续强制(k30-preview-normal-backend.json)。手机仍Dozing，**本轮没有新的正常前端API200/界面通过证据**。forward19374已移除，桌面停止按需；项目/真实Cookie未清。README/VALIDATION/LSP/PROJECT_PREVIEW已同步。旧段落“当前APK/句柄”为历史。

下一步保留完整目标：解锁后优先目录组合UI、预览启动器/旋转/返回/视觉和原有21项UI回归，再更新稳定artifacts。独立推进受控预览helper/HMR、LSP剩余覆盖、真实模型配置安全元数据核查及实际编程、完整备份恢复。真实GitHub登录/推送、ARM64真16K、Android10–16矩阵、实平板/外接屏/外设、压力/电池、安全/许可证/签名发布仍未完成。无remote/提交/发布。历史被自动审批拒绝的Windows Temp删除、Docker、全局ADB UI组合动作不可重放；本轮无新增拒绝。

## 最新：项目目录衔接已实现，后端8项通过；K30锁屏等待界面验收（2026-10-28）

本轮完整读取了明确指定的 goal-objective.md（dfb85330 附件），继续完整v2.0，没有缩小目标。上一轮为明确progress；本轮也有实际代码和K30后端证据，不标complete/blocked。没有子代理授权，未使用子代理。用户途中说“继续”，是继续当前完整任务。

核对固定官方源码（真正目录为.cache/opencode-upstream/packages/{cli,server,core,app}，不是packages/opencode）发现serve --service的process.chdir(HOME)是守护进程设计，实际Agent按Session.location.directory工作。保留官方二进制/UI不改，通过既有location/session API补齐Native入口。新WorkspaceContext：默认/workspace、私有设置保存当前目录、严格本地origin+规范base64 server key+session URL解析、原生认证获取会话目录、generation防迟到响应覆盖用户的新选择。NativeCode页面history/page完成和切回Code时观察会话，普通输入点击不重复查询。

Files VM/Page新增在当前目录或文件夹“开始OpenCode会话/打开终端”：启动已有环境（需要时）、校验实际目录、调用官方API创建会话后走官方/server/<key>/session/<id>链接；事件由VM保留，Root把待打开链接rememberSaveable到实际导航确认。未保存编辑器禁止直接开始会话；项目切换时保留dirty文本并提示确认，不静默丢失。/workspace内文件面板跟随项目，树外项目仍支持Linux终端/桌面，但Native文件面板现有范围不扩张，不谎称全部目录都能在此浏览。

ProotCommand支持显式cwd（默认仍/workspace）；PTY新会话使用当前项目，已有PTY不自动cd；TerminalSessions.open返回Deferred供Native入口等待，并集中管理焦点。静态bash wrapper使用独立argv和cd -P，支持中文/空格/shell字符，保留symlink/..物理语义。终端标签显示初始目录。RuntimeService尝试注册默认/workspace项目，失败不终止已经就绪的后端。

Desktop按当前项目启动，并默认打开Thunar。desktop-session.sh在真正dbus-run-session内以umask077保存run/dbus-address，desktop-open-project.sh读取地址、同套XDG环境、cd -P后exec thunar "$PWD"；桌面可信用户手势“项目文件夹”触发内部OPEN_PROJECT，可打开新目录而保留X server。额外Native进程受4个semaphore槽限制，异常/取消/停止清理，stdout不泄露地址。地址文件停止即删。signed guest helper拷贝改用NOFOLLOW open，避免跟随目标symlink。Main/desktop现有页面与认证/SAF/返回逻辑保留。

**已编译、assembleDebug/AndroidTest/lint通过；当前源码已安装K30。** ProjectDirectoryBackendTest首轮10.115秒通过真实会话目录、PTY pwd、旧shell目录不变、实际Thunar初始/切换窗口、同一X PID、特殊字符不执行、服务/DBus清理。随后加入symlink/..实际物理路径；与BundledPlugins2、LSP3、Agent1、MCP1合计**8项全过39.189秒**，证据k30-project-directory-backend-regression.txt。真实Agent仍用scripted model protocol夹具，不算云模型验收。

**UI组合测试还没通过，原因已由当前状态证明是设备锁屏，不能当产品/布局故障。** ProjectDirectoryAcceptanceTest的规范URL/generation方法通过，组合方法在第一次点击前无Compose hierarchy。逐步诊断显示Activity=CREATED、finishing=false、destroyed=false、focus=false、locked=true、interactive=false，原生ComposeView存在；没有Crash。加KEEP_SCREEN_ON/turnScreenOn无法越过锁屏，未绕过安全锁。系统screen_off_timeout仍2147483647，stay_on_while_plugged_in仍15，USB powered=true。最后只读dumpsys power仍Dozing。已通过request_user_input_async请用户解锁并打开OpenCode，**这个必需答复仍待用户，不可因时间过去当作已解锁，不要重复发同一问题**。用户之前的“继续”发生在该问题之前，不是解锁答复。

组合UI测试覆盖：Native文件夹按钮→官方页面真实composer、HTML5 history→目录、Native文件→新终端焦点、dirty文档保护、真实桌面按钮→Thunar；需要解锁后跑，并修正实际发现的问题，不能以后台测试代替。首轮及等待失败证据分别k30-project-directory-first-ui-failure.txt、k30-project-directory-ui-wait-failure.txt、k30-project-directory.txt。后者有明确锁屏诊断。Windows临时目录历史删除拒绝仍不能重放，本轮无新增拒绝。

当前开发build输出hash（尚未替换artifacts稳定包，UI未验收不要称发布）：APP **653518076字节 / 620d6cf10ccf0c1a46d5ebc6e48aa4b6f5d5f588eef9a4994ad7537e0986bee4**；test **3086295字节 / 91ca35e0a6785f9dc658f9aa06c53b5c280db2b88a44974ca38d738d35112fb9**。project-context-build-hashes.json和project-context-apk-check.txt已保存，check_apk确认API29/36、匹配官方dev、host/APK16K静态对齐。artifacts仍上一M4稳定21项版61ac.../9c310...，不要误认为新目录功能包已覆盖。

最终结束全部instrumentation，force-stop自身测试进程后正常profile OPEN_WORKSPACE启动；最后通过临时19374 ADB forward只读/api/info确认匿名401，表示认证后端已起来；**尚无法证明新UI正常连接，因为手机仍Dozing，WebView要解锁后恢复。** forward19374已移除，无19226新转发。K30正常profile后端运行，Desktop已停、测试Thunar/PTY均清理；**无活跃exec/test句柄。** 用户项目/真实cookie未清，WorkspaceContext恢复原值。测试只删自身创建的session ID/带UUID目录，没有手工改用户SQLite；项目库存的失效测试条目由上游一小时sweep归档。

接下来优先用户解锁后的ProjectDirectoryAcceptanceTest，再做当前源码FullScreen/OpenCode cookie/文件/PTY/Bridge/Hybrid回归，通过后同步artifacts/hashes并正常profile复查。锁屏期间仍有独立后端工作可做，不是整个goal impasse：LSP external_directory→read审批与file宏/覆盖、项目预览（必须先隔离跨端口cookie，不能直接塞进现有cookie jar）、真实模型/账号安全元数据与GitHub登录推送、完整备份恢复。完整M4真实平板/铰链/外设，M5 ARM64真16K/Android10–16/压力/后台/电池/安全/许可证/签名发布仍未完成；无remote、未提交、未发布。不要把“默认目录”缩为改守护进程HOME，也不要把新入口后台通过冒充界面通过。

以下所有“当前状态/APK/句柄”均为历史，以上为准。

## 最新：M4 自适应分屏、鼠标文件菜单与桌面服务清理（2026-10-28）

本轮继续完整 v2.0 goal，仍未完成。已重新完整读取最初指定的 pasted-text-1.txt（f131ddf6 附件），内容与 goal-objective.md 的完整计划相同。没有子代理授权，未使用子代理。

新增 AdaptiveWorkspace.kt：按实际窗口宽高选择 Compact、Expanded、Hybrid；文件侧栏、底部/右侧终端、Code 与桌面同时显示；可拖动/键盘/无障碍分割线、按宽高分类及布局分别记忆比例。悬浮球新增工作区布局，支持单页放大/恢复、面板开关、Ctrl+左Alt快捷键，AltGr不拦截。MainActivity保留原SaveableStateProvider、浏览器返回、上传和退出确认。嵌入式终端/文件栏紧凑化，文件右键/长按与⋮进入同一操作菜单，右键不打开编辑器。隐藏当前活动侧栏/终端会将焦点返回主页面，选分屏布局会进入Code。docs/WORKSPACE.md说明阈值和范围。

第一版使用movableContent跨多层BoxWithConstraints子组合，真机出现SlotWriter数组越界；已彻底改为同一平面父节点和固定页面key，只改变矩形位置/尺寸。仍可见的Native WebView不重建，中文草稿、选择范围和JS状态在切换、拖动、放大和窗口变窄后保留。首次失败证据保留。测试使用真实尺寸约束变化，不靠修改LocalDensity冒充窗口变化。

K30真实横屏同时显示官方WebUI与认证XFCE/Chromium；Code自己的Cookie API=200，拖动前后Code/Desktop WebView对象相同，Activity横竖屏后Linux supervisor身份不变。Chromium只运行本地测试页，测试进程显式--no-sandbox，不启用用户兼容设置；标准沙箱失败仍未解决。早期截图花屏实际出现在canvas读回/Compose软件采集路径，远端canvas PNG清晰；最终去掉读回，使用自身Window PixelCopy并检查实际画面预期绿色区域，最终图已目视确认清晰：.cache/evidence/k30-hybrid-presentation.png。禁止把先前受影响的k30-hybrid-real-services/k30-hybrid-chromium图当最终显示证据。

组合回归发现DesktopService仍用stopSelf(startId)，STOP有新id导致宿主Service及AndroidBridge desktop lease残留；修复为stopSelf()，启动用job.isCompleted守卫避免取消清理时双owner，STOP也处理已完成job。RuntimeService STOP同步处理completed job。Hybrid验收停止两服务后明确等bridge-auth.json移除，后续Bridge末lease/监听线程退出也通过。

**当前源码最终构建 assembleDebug/AndroidTest/lint通过；K30最终21项全部通过，60.633秒**：Adaptive3、真实Hybrid1、Fullscreen5、OpenCode强认证1（staleCookie=true）、文件UI3（含右键/长按）、实际PTY终端1、Bridge7。无跳过。证据k30-adaptive-final-regression.txt，另有stop-fix阶段21项58.404秒。APK check确认SDK29/36、ARM64、host/APK16K对齐、匹配官方dev摘要；静态16K不代表真实ARM64 16K通过。

制品均已覆盖、安装及更新SHA256SUMS-arm64.txt：APP **653517994字节 / 61ac0375e1d9ab0461de79e2e4e53296bd0be7c3f95cf9269d3c4aa64ff20669**；test **2835723字节 / 9c31034db6ad3bdc30289723dc5949be10bcae8def57550bc4ac1638df718f31**。ZIP增量留空间使大小未变，但哈希/实际新测试均确认源码生效。x64制品仍旧，未重建。

最后结束instrumentation，force-stop自身测试进程后正常profile OPEN_WORKSPACE冷启动；只读自身WebView DevTools复查API=200/root873/无连接错误，用户localStorage server key与HttpOnly cookie保留，证据k30-adaptive-live-connection.jsonl。19226 forward已移除。**K30目前正常OpenCode前台+后端运行，桌面停止按需，测试Chromium已清理；无活跃exec/test句柄。** 下一次安装/测试先说明将中断会话，继续使用isolatedBrowser=true，禁止清真实用户cookie/项目。

下一步保留完整目标：核查生产默认目录（上游serve --service chdir/root，Native文件/终端/workspace需统一），项目开发服务器预览与跨端口cookie隔离，真实模型/账号与GitHub认证/推送（先查安全配置元数据，别直接断言无配置）；LSP external_directory→read审批/file宏/其余查询配置覆盖；完整备份恢复。M4实平板/铰链/自由窗口/外接显示/实体蓝牙外设仍需专项验收；ARM64真16K、Android10–16矩阵、后台/压力/电池、安全/许可证/签名发布仍未完成。未设Git remote/未提交/未发布。旧Windows Temp删除自动审批拒绝仍不能重放，原目录保留；本轮没有新增审批拒绝。

以下旧段的“当前APK/句柄/下一步”均为历史，以上为准。

## 最新：前端自动连接修复，K30 已部署并留在正常工作区（2026-10-28）

用户中途明确报告“现在OpenCode前端怎么没有自动连接后端”，已优先完成修复。现场通过自己APK的WebView DevTools只读探测：origin49374、root873、HttpOnly cookie存在但页面fetch /api/info=401。旧代码只看cookie名称存在就跳过配对，故显示UI却未认证。没有输出/保存cookie值；旧登录状态与项目未清空。

新 BrowserAuthentication.kt + LocalOpenCode.browserReady/browserCookie：只用浏览器cookie验证API，不用Basic掩盖浏览器失败；失效时Native HTTP消费官方一次性链接，校验302/路径/HttpOnly Set-Cookie，再写CookieManager、验证200后恢复原官方WebView。过期/已用链接通过RuntimeService.RECONNECT向现有活进程申请新链接，不重启后端/PTY/桌面。一次性链接不再进入WebView JS/历史，避开旧SW拦截。API401先复查info，避免把提供商自身401误判为本地认证坏了。RootUI保留全屏/上传/返回/旋转能力；Native状态有连接/重试，未重写官方UI。

RuntimeService另修复取消清理期间的双owner竞争（isCompleted守卫）和startId变化导致stopSelf(startId)未真正停止的问题（改stopSelf）。MainActivity.OPEN_WORKSPACE是显式打开/启动已安装工作区的Activity action，通知“返回OpenCode”使用它。普通Launcher仍不静默后台自启；在运行环境启动成功会自动进入OpenCode页。新action在冷启动和onNewIntent处理，旋转不强制切页。

OpenCodeAcceptanceTest现在实际检查WebView自己的Cookie API=200，不能只把root高度>100当连接成功。isolatedBrowser=true/staleCookie=true内注入坏HttpOnly cookie、提前消费链接，自动重新配对通过。首次回归遇到“正常UI已自动消费链接”测试竞争，已让测试控制UI并单独签发/消费真实链接，未弱化认证。证据k30-webui-reconnect.txt单项4.380秒。

最终当前源码K30回归全部通过：连接+Fullscreen5共6项17.350秒（k30-connect-fullscreen-regression.txt）；managed插件2+LSP3+真实Agent1+MCP插件1共7项32.211秒（k30-agent-lsp-final-regression.txt）；Bridge7+FilesUI2+实际Chromium GUI桌面1共10项33.616秒（k30-connection-bridge-desktop.txt）。合计23项，无跳过。Node协议6项也通过（lsp-protocol-unit.txt），clangd实际PGID=PID确认，graceful/kill/close等待均已加入；全套assembleDebug/AndroidTest/lint通过。

当前最新APP/test均已安装并覆盖artifacts：
APP 653517994字节 SHA256 **780af5a3a44d022a75b3512cbe0a091b3f1a2bb7137ba37afb2ff2d95c01d1f2**；
test 2835723字节 SHA256 **594ce46f00326f50f35578b38b2d555fd040fb97e27202786882d3d6ada05dad**。
SHA256SUMS-arm64.txt更新，check_apk.py验证host ELF16K、APK16K、SDK29/36、官方匹配dev摘要通过；ZIP确认BrowserAuthentication DEX与LSP源码真实包含。根镜像developer-v1没有改，按需语言服务实际安装18包；不要称为基础镜像已内置。

最终已结束全部instrumentation，force-stop自己测试进程后用正常profile显式OPEN_WORKSPACE冷启动，现场页面fetch API=200/root873，HttpOnly cookie保留，记录k30-live-webui-connection.jsonl。ADB DevTools tcp19226转发已删除。**当前无活跃exec/test句柄；K30用户正常OpenCode前台+后端运行中**，Desktop按需停止，未留下no-sandbox诊断浏览器。后续APK/测试会中断此用户会话，先说明，并继续用隔离profile；不要清理真实cookie、localStorage或项目。

下一步仍是完整v2.0，绝非完成：用户文件/桌面优先，继续M4 Expanded/Hybrid自适应分屏、比例记忆、输入外设/IME。LSP还需恢复完整外部文件external_directory→read审批（当前project-only限制不能当最终完整能力）、file插值、其余查询/配置覆盖。核查默认工作目录：上游serve --service会chdir HOME=/root，Proot cwd=/workspace；确保官方默认项目、Files、Terminal、GUI的工作目录协作。真实模型/账号尚不能靠script fixture充作验收，应先检查实际安全元数据/可用服务，别无证据假定用户没有配置。M5 ARM64真16K、Android10–16、压力/后台回收、完整备份恢复、安全/许可证与签名发布仍需完成。无remote/未发布/未提交git，禁止擅自对外消息。

本轮额外自动审批拒绝了一条“Node测试+清理Windows临时目录”的组合命令，仅报blocked by policy。不要换工具重放删除；4个很小的旧临时目录保留。Node测试已改在工作区.cache/lsp-unit创建可检查夹具，不递归清理。已向用户commentary说明。历史全球ADB UI/截图与Docker拒绝仍不能重放；自己APK instrumentation/Compose根截图和只读自己WebView调试可用。没有子代理授权，禁止spawn。

上一轮为明确progress（代码、真实执行与证据改变），完整goal已读，不标complete/blocked。以下各旧段的“当前句柄/旧APK哈希”均为历史，以上为准。

## 最新：真实 Agent、MCP 与 Linux LSP（2026-10-28，进行中）

上一目标轮有明确进展：官方未修改 dev Agent 已在 K30 实际写四文件 C 项目，执行失败测试、修复、重测并提交/推送本地 bare Git；真实 shell once/reject 权限门控通过。强制结束 supervisor 后重启，同一 SQLite/state 里的 7 个投影消息 ID 不变，后续模型协议上下文仍有原回答和工具历史。AgentWorkflowTest 首次 10.036 秒，后续带流式证据也通过。用明确标注的本地 scripted OpenAI Chat/SSE fixture，不冒充云模型。`docs/AGENT_ACCEPTANCE.md` 记录细节。最初 /api/info probe 把顶层version当data.version的测试错误已修复，失败记录保留。

AgentExtensionsTest 4.537 秒通过：真实 Debian Python MCP stdio + Streamable HTTP JSON/SSE 响应，外部 Promise 插件工具/hook/RPC，实际子进程调用记录与结果值检查。固定 dev 无旧 /sse 握手回退。MCP connected 与 tool registry 发布异步，测试通过测试插件 RPC 等实际注册表齐备再提交提示，不能靠重置模型连接重试。首次错误夹具/注册表 race 证据单独留存。

新功能：RuntimeService.INSTALL_LANGUAGE_SERVERS + Runtime页按钮，通过隔离 apt 源文件（默认USTC/可mirror official、Debian keyring签名）安装clangd/pylsp/pyflakes/pycodestyle，不覆盖用户apt源或rootfs镜像。安装与运行时/桌面互斥。LanguageServerInstallTest 首次47.653秒通过，新增16包10.8MB；版本clangd19.1.7/pylsp1.12.0。pyflakes/pycodestyle为后续补充，当前正跑安装测试。

新 BundledPlugins.kt 把签名APK plugins/opencode-android-lsp/{package.json,index.mjs,client.mjs,config.mjs} 安装到上游标准全局plugins目录。官方后端/官方WebUI二进制均未修改。因为固定dev仅有LSP schema/GUI及write TODO、无运行模块，Linux侧Promise插件补齐客户端和工具，详见docs/LSP.md；不是在Android重写Agent。插件有stdio帧界限、取消/超时、push/pull诊断、生命周期、只读查询、写/edit后诊断、最多4服务/项目、空闲120秒关闭。普通JSON/JSONC lsp配置和环境插值，未实现file插值；其他语言/操作须继续测试，不把enum当作验收。

AgentLanguageServerTest 实际clangd已通过：语义missing_identifier、写入后自动诊断、修复清空、定义/引用/hover/符号及supervisor回收。BundledPluginsTest2项（不覆盖用户插件、signed更新、路径/sidecar symlink保护）一并通过3项5.821秒。首次版本bug是no-op didChange增加版本后服务器不重发相同诊断，已改未变内容保留版本；RPC不运行服务pid=undefined造成JSON输出拒绝，已改null。相应初次失败证据保留。

当前源码再加入Python Unicode文件/pyflakes诊断集成与相对read deny测试，并修复LSP权限资源为上游FileAccess相对路径，别名/真实目标同时检查，自动反馈也须read许可。这两项尚未执行。当前最新gradle assembleDebug/assembleDebugAndroidTest/lintDebug通过17秒；最新APP和test已安装K30。发布artifacts仍旧哈希，**尚未覆盖到本轮新制品**，完成验证后必须copy/hash/check_apk。基础developer-v1镜像没有改，不要声称clangd已在rootfs内置。

正在运行的唯一句柄：exec_command session **59260**，`LanguageServerInstallTest` 追加pyflakes/pycodestyle安装，输出到`.cache/evidence/k30-lsp-linters-install.txt`，最后只收到测试开始。必须轮询同一句柄，不能因为观察超时重启。无其他活跃工具/子代理。下一步：完成安装→AgentLanguageServerTest(3项)+BundledPluginsTest→回归核心/全屏/Bridge/桌面→制品同步。然后继续M4 Expanded/Hybrid、自适应多面板及完整v2.0剩余工作。未标记goal完成；ARM64真16K、Android版本/外设/压力/备份/模型/账号/签名发布等还未完整验收。

提醒：最新完整goal文件在本轮开头已再次read；用户优先级仍全屏上传返回→文件/桌面。无子代理授权，禁止spawn。不得重放此前自动审批拒绝的全局ADB UI/截图及Docker操作；使用自己应用instrumentation。K30 serial<serial>，保持关闭自动息屏。当前仍可有大量不依赖用户输入的进展，不能标blocked。

## 最新：文件传输与 AndroidBridge（2026-10-28）

上一目标轮有明确进展，本轮继续原始完整v2.0目标，未缩小/标记完成。用户最近优先级为全屏/悬浮球/上传/返回，然后文件管理与桌面；前三项和桌面已验证，本轮补齐复制/移动/分享和系统桥接。

ProjectFiles.transfer 支持暂存完成后发布、同卷移动、重名另存保留扩展名、普通文件模式及符号链接对象保留、禁止向自身/后代复制、逐块取消。私有 backups/project-file-staging 仅清理带标记的旧UUID操作。ViewModel 保留待粘贴项、显示进度/取消；文件菜单已实接复制/移动/系统分享。FileSharing只暴露cache/shared-files的只读副本；FileProvider仅该根，未暴露workspace本身，跨UID读成功/写入拒绝已测。

AndroidBridge.kt + BridgeRequests.kt + APK guest/android-bridge.py：随机抽象Unix socket，真实peer UID+256位每次启动令牌、512KiB帧上限、4并发、600秒请求超时。无HTTP系统API/无普通WebView JS桥。Native UI 明确确认后打开HTTP(S)、读/写剪贴板、通知、SAF导入导出、文件分享；后台只排队/提示，禁止后台直接拉浏览器。RuntimeService/DesktopService各自租约，最后退出清理认证文件0600、连接和监听线程；client断开撤销请求。连接监测使用Os.poll，避免LocalSocket超时EAGAIN被当作断线。NativeMain处理onNewIntent并setIntent用于通知恢复。

PATH新增/android-runtime/bin，BROWSER=/android-runtime/bin/android-open。含xdg-open URL、android-open、android-clipboard get/set、android-notify、android-share、android-import、android-export；non-URL xdg-open委托原Debian处理器，不改用户系统脚本。gh --web继承BROWSER，真实账号登录尚未验收。脚本随APK更新、仍经Linux解释器/PRoot执行，不直接宿主exec可写ELF。临时桥接令牌不进参数/环境/日志。

本轮K30：文件仓库+跨UID只读分享6项0.472秒；Bridge7+文件UI2共9项21.225秒无跳过，实际Linux helper→私有IPC→原生UI→SAF真实字节/剪贴板/本地通知均验证。系统选择器、浏览器和分享接收结果用Intent桩，未向外发文件/登录账号。测试Provider读写都MANAGE_DOCUMENTS，控制IPC拆为test.control并检查AppUID，不能再通过受保护DocsProvider直接call(grantFixture)。Data文件只在Provider初始化写入，保持mtime稳定。后台排队/取消130/坏令牌/其他UID/危险scheme/断线/独立租约和线程清理通过。

追加当前代码回归：核心/文件8项9.460秒，FullscreenWorkspaceTest5项12.371秒（isolatedBrowser=true），真实Chromium GUI/桌面1项12.847秒（显式includeChromiumGui=true，仍未启用用户持久compat标记），build/lint通过。认证/会话真实profile未清理，clipboard测试恢复原值且不记录原内容。当前无活跃测试/exec句柄。

最新ARM64制品已安装K30并覆盖artifacts同名文件，APP 653517539字节，SHA256=69db7675031d4fe1fc6f5cd24f9f2a44c6ae7def139eb5f25eda1936ee2181f9；test 2835478字节，SHA256=7a0c186d53e3c4a6ca9fd2dd18d4fc9442fe9fea18f1deb56015ec75c7d452ff。SHA256SUMS-arm64.txt已更新，宿主ELF/API29/36/APK16KB及官方OpenCode摘要检查通过。ZIP检查确认APK包含bridge helper及Bridge/FileSharing DEX类，测试控制Provider仅在test APK中。下面旧段落的旧制品哈希只作历史，不代表当前文件。

下一步优先：M1官方Agent实际写文件/运行测试/会话恢复、模型配置，再LSP/MCP/插件；之后平板Expanded/Hybrid、IME/外设、备份/生命周期/压力、ARM64真实16K、发布。模型可用明确标注的本地脚本provider fixture验证真实官方Agent循环，不伪称云模型通过；缓存上游packages/server/test/provider.test.ts给出providers.custom package=@opencode/ai/providers/openai-compatible配置，session-create.test.ts给出/api/session创建参数。源码固定f993a89d...版本2.1.4-dev。尚无用户云API键/发布仓库/签名key，仍有大量无外部输入可推进工作。

设备<serial>仍在线，K30/Android16/ARM64/4KiB，AppUID10333；保持screenOff=2147483647、stayon=15。不要复现已被自动审批拒绝的全局ADB UI/截图或Docker操作；本轮仍只使用自己应用的instrumentation。无子代理授权，禁止主动spawn。不要重复问已授权的 routine 操作。

## 最新：全屏与文件/桌面（2026-10-27）

用户要求：去掉固定标题和顶部黑边；全屏、漂亮的可拖动悬浮球导航、横竖屏；OpenCode 系统图片/文件选择；返回先作用于 WebUI、退出二次确认；随后推进文件管理与桌面。均已实质实现，仍不能把完整 v2.0 目标标记完成。

全屏/悬浮球/旋转/返回弹层与历史/退出确认/图片和多文件实际字节上传/取消重选：FullscreenWorkspaceTest 5 项通过。上传测试专用 IsolatedWebTestRunner + isolatedBrowser=true，独立 WebView suffix，不清理真实用户 Cookie。测试 Provider 仅合成 PNG/文本，初始化后保持文件不变；曾因每次 openFile 重写 mtime 导致 Chromium NotReadableError，已修复测试夹具。

文件：ProjectFiles、ProjectFilesViewModel、ProjectFilesPage 接入真正 /workspace，浏览/搜索、新建、UTF-8 编辑、SAF 导入导出、重命名、回收站恢复、外部修改冲突检测、路径和链接检查。6 项最新核心/文件回归通过，14.347 秒（仓库3+文件UI1+官方OpenCode1+终端1）；新补根目录被替换为链接的防护后仓库3项又通过0.127秒。文件复制/移动/分享和完整 SAF 原生交互矩阵仍待推进。

桌面：DesktopService 独立 specialUse FGS；APT 中科大/官方源可选，单独源配置，不替换用户系统源，不关闭签名验证。实际 K30 在线安装 XFCE/TigerVNC/noVNC/websockify/Chromium，186.065秒通过，补齐实际 apt 更新/安装验收。桌面使用 PRoot developer1000内部身份（宿主仍AppUID），私有XDG，工作目录共享。5901/6080只监听loopback，HTTP Basic+WebSocket派生凭据+Origin校验，避免跨端口认证Cookie。新增 CredentialVault namespace，默认server别名和文件保持原值，desktop独立。

桌面等待真实窗口管理器就绪，测试等待真实像素及视觉帧；早期背景截图不作为桌面完成证据。真实 Chromium GUI 本地 data URL 专用 profile 通过，绿色采样1328点、260色，画布392×820匹配窗口393×821；K30自身截图 `.cache/evidence/k30-chromium-gui.png`。最新增加浏览器设置原生点击/取消不改设置、横屏后重连，通过11.026秒。正常停止先写stop-request由会话清理，5秒再强杀；重启清理仅当前UID且PID已死亡的私有X11锁/socket。rootfs更新与桌面启动用RuntimeCoordination原子状态互斥。

Chromium156.0.8078.47标准Linux沙箱在K30退出133/SIGTRAP，保留失败；显式测试 `--no-sandbox` 时 headless实际HTML通过2.219秒，GUI也通过。没有启用用户持久兼容标记。产品默认标准模式；“浏览器”按钮进入原生警告后可明确启用兼容模式，独立profile，说明仍可访问本应用文件及重要账号交给Android浏览器。不要隐藏Chromium自身不安全参数提示，也不要声称Linux沙箱通过。

曾发现rootfs不完整，使用已校验APK包修复并保留原目录备份；修复/恢复2项69.326秒通过，之后只读StorageDiagnostic确认marker、清单、curl/Bash/OpenCode完整。InstallerRecoveryTest加fake context路径断言；没有删除用户workspace/home/state。

当前Android测试runner已改为 `io.github.opencodeandroid.ui.IsolatedWebTestRunner`；Espresso3.7.0/Runner1.7.0/ext.junit1.3.0适配API36。Compose测试所需coroutines-test仅debugImplementation，发布包无测试dispatcher。Gradle当前可 `--offline` 构建/lint。

ARM64最新制品已整理并安装K30：artifacts/opencode-android-full-arm64-debug.apk，653517330字节，SHA256=87d09b68fc118ef14c65a07019f0034d0cb28e60c40c444ea7ced133f068aac7；test.apk SHA256=ecfe506553a3427da010dbe82f3c341696c72983bd9e72d1edd8e09ae71fb715。SDK29/36、所有宿主ELF和APK16KB对齐、官方OpenCode摘要检查通过。x86诊断制品仍旧，不代表最新代码。当前无活跃测试/exec会话，桌面和OpenCode测试后停止，用户默认Chromium兼容标记未启用。

仍要：AndroidBridge/完整SAF/分享，Agent/model真实工具调用与恢复、LSP/MCP/plugin，完整桌面IME/外设及Chromium安全评估，平板多面板，ARM64真实16K，系统生命周期/压力/备份/签名发布。不要为剩余硬件验收单独把目标提前blocked，仍有大量可推进工作。不要创建子代理（用户未授权）。

任务：按完整 v2.0 计划持续实现，目标尚未完成，不能把已完成的原型当作 M0–M5 完成品。

用户澄清：OpenCode 2 指官方仓库的 **dev 分支**。已固定 `f993a89d13c73ff6f6537d6b97e78e138a3ea363`，源码版本 2.1.4，构建版本 `2.1.4-dev.f993a89d13c7`，Bun 1.4.4。

用户提供 **Redmi K30 5G**，ADB serial `<serial>`，Android 16/API36、ARM64、4096 字节页、UID10333。关闭自动息屏的原设置保存在 `.cache/k30-display-original.json`，不要覆盖；screen_off_timeout=2147483647、`svc power stayon true`（15），重启后也已重新设置。为清理早期失败测试留下的内核循环进程已重启，用户随后明确回复“已解锁”。

已实现：Android Host/Compose 自适应导航、私有 rootfs 安装、摘要验证与安全解压、原生目录 PRoot/Loader、JNI PTY、前台服务、上游官方完整 OpenCode 与内嵌 WebUI、Keystore、本地健康/版本/匿名拒绝/一次性配对、严格分离的受信终端资产桥、多 Bash 会话。

K30 已安装包含 629 个软件包的开发套件镜像并保留旧 rootfs 备份。首次升级/恢复 2 项通过（79.445秒）；重启后回归 7 项通过、1 网络项明确跳过、0失败（35.098秒）。C 动态/静态、多文件链接、Clang/C++、Node 子进程、Python subprocess/venv/pip、Rust/Cargo 离线构建全部通过。官方服务/WebView、PTY/终端、进程清理通过。详见 VALIDATION.md；不能称 M0–M5 已完成。

工程诊断模拟器：`emulator-5582`，专用 AVD `OpenCode_API35_4K`，Android15/x86_64/4096字节页。API36/16K x86_64 模拟器已停止；Debian amd64 glibc 的4KB ELF失败需要保留。ARM64 glibc64KB对齐，ARM64 16KB实际设备仍缺。

开发镜像：干净 WSL chroot `/root/.cache/opencodeandroid/developer-build/rootfs`，QEMU ARM64 user8.2.2，Debian Snapshot20261027T000000Z；551个新增依赖的deb逐个SHA256校验，apt验证InRelease签名。构建已完成并导出 `.cache/debian-developer-arm64.tar.gz`，SHA256为1347d2e42b67863aa53923a3d74b64157faa02c546a92b4b50fb5907260f8d28，普通文件/硬链接复制预算1.35GiB。镜像及OpenCode通过 `tools/bundle_developer.py` 合并元数据；详见 DEVELOPER_PACK.md。

PRoot本地补丁：EXITKILL清理tracee、先读fork事件PID再读寄存器、close_range已知调用/ENOSYS回退。最后一项解决K30 Python dpkg配置挂起，Android内核安全策略未关闭。原生启动器重置子进程信号并关闭除0/1/2以外的继承FD，Java ProcessBuilder已改为JNI NativeProcess，确保实际SIGKILL和reap。补丁摘要变化时完整清理旧对象，防止Windows/WSL时间戳导致syscall枚举旧对象混用。

网络：官方 deb.debian.org CDN 间歇超时；Android Java和Debian curl对照也同时超时，DNS一致、无Android静态代理/PAC。不要把它当作已修复或全部网络通过。当前继续对照 USTC 镜像。GuestNetwork用Android活动网络刷新DNS，并在rootfs安装/更新后刷新；curl/wget本身已装入镜像。一次在用户锁定阶段发起的ADB instrumentation客户端挂起，解锁后停止了该任务专属ADB客户端并重新运行，未重启ADB服务器或影响其他设备。

开发机器：Windows PowerShell；SDK `%LOCALAPPDATA%/Android/Sdk`，API36、NDK26.1.10909125、CMake3.22.1；Java21。WSL Ubuntu 可以构建但网络直连不可用，Windows 网络正常。Linux NDK r29 已在 WSL `/root/.cache/opencodeandroid/android-ndk-r29`；固定归档在 `.cache/downloads/`。PRoot 编译：`ANDROID_NDK=/root/.cache/opencodeandroid/android-ndk-r29 bash native-host/build.sh arm64-v8a x86_64`。脚本优先本机 `.cache/proot-src` 源码，避免 WSL 网络。新原生程序位于 `app/src/main/jniLibs/<abi>/`，全部为生成文件。

上游源码在 `.cache/opencode-upstream`，Windows Bun 1.4.4 在 `.cache/tools/bun-windows-x64/bun.exe`。依赖已 `bun install --frozen-lockfile --os=* --cpu=* --libc=*`。初始 ARM64 二进制在 `packages/cli/dist/cli-linux-arm64/bin/opencode`，x64 在 `packages/cli/dist-x64/cli-linux-x64/bin/opencode`；工具 `tools/build-opencode.ps1` 可重新构建并打包。

构建：`gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug` 为 ARM64；加 `-PdiagnosticX86=true` 为诊断 x86_64。两种属性共用 Gradle 输出目录，不能同时运行两个属性构建；切换前先复制所需 APK。`tools/check_apk.py` 检查所有宿主 ELF、ABI、内嵌摘要、API29/36、native 提取和 zipalign。`artifacts/` 存阶段性 APK，需在任何后续代码修改后重新构建复制。

验收命令用 `adb -s emulator-5582 shell am instrument -w -r -e class <测试完整类名> io.github.opencodeandroid.test/io.github.opencodeandroid.ui.IsolatedWebTestRunner`。Loader 网络/编译器检查需 `-e includeNetwork true`。OpenCodeAcceptanceTest 检查真实服务和 WebView 高度；TerminalAcceptanceTest 检查真实输入/PTY/尺寸/Ctrl+C/多会话。未传网络参数时相关项明确跳过。

已发现并修复：aapt 会解压并去掉 `.gz` 后缀，故资产改为 `.pack`；Android 禁止部分 host 硬链接，解压时安全回退为复制普通文件；WebView 默认 WRAP_CONTENT 导致 CSS vh/dvh=0，设置 Android LayoutParams 为 MATCH_PARENT，官方前端未改动。

一次组合 ADB UI 操作/截图脚本被自动审批拒绝，原因仅 `blocked by policy`；没有重试/变形绕过。后续使用项目内 Android instrumentation 验证界面。已向用户明确说明该拒绝。不要再次提交被拒绝的自动 UI 脚本。

下一步：完成联网/apt验收，更新当前ARM64制品与哈希；增加受控文件/URL AndroidBridge和SAF；Git/gh/SSH完整流程、LSP/MCP/插件/开发服务器；模型/Agent真实改码测试（可先明确标注的测试provider fixture，尚无用户云API配置）；桌面与自适应分屏；完整备份恢复；ARM64 16KB和Android10–16。不要宣称完整里程碑完成。无远程Git仓库、发布密钥或分发设置。
