# OpenCode 独立更新包

目标是分别维护签名 APK 中的 Android Host/Loader、Debian rootfs、官方 OpenCode 前后端对及 apt 软件包。更新官方 UI 时始终随其完整服务端共同构建，不修改或混搭官方前端源码。

当前已实现更新包生产、同一 APK 签名身份验证、签名 HTTPS 更新源与下载、原生 SAF 导入与版本确认、独立活动程序、状态快照、可恢复切换、真实服务健康检查及手动回退。入口在“运行环境 → OpenCode 更新”。下载可以在本地开发服务运行时进行；安装前停止 OpenCode、终端和 Debian 桌面，再核对并确认版本。不同官方数据库 schema 版本间的迁移矩阵仍待完成。

## 在线更新源

填写发布者提供的 HTTPS `.ocafeed` 地址，点击“检查更新源”，核对版本、提交与大小后选择“下载并验证”。下载成功后进入与本地导入相同的原生安装确认；检查、下载均不会执行或切换程序。当前仓库没有远程地址或正式发布，因此应用不预设虚构的在线源。

公开 GitHub 仓库发布后的稳定源模板为 `https://github.com/OWNER/REPO/releases/latest/download/opencode-android-full-arm64.ocafeed`。预览版本可以使用对应标签的 `/releases/download/TAG/` 地址。这里的 OWNER/REPO/TAG 是配置占位符；草稿发布在公开前不可由匿名客户端下载。v1 不接收私有仓库令牌，也不宣称不同提交一定按版本号递增；签名源中的完整版本由用户核对，可以审阅旧的合法签名版本。

`.ocafeed` 使用 8 字节 `OCAFEED1`、两个大端 32 位长度、JSON 原始字节和签名。JSON 最多 16 KiB、签名最多 8 KiB、整个响应最多 24,592 字节；先验证当前 APK 唯一签名证书，再以严格 UTF-8、深度 16 上限解析。签名覆盖 kind/format/ABI、版本、提交、最低 Host、HTTPS 包地址、ZIP 大小/摘要、包内 manifest 摘要和完整程序摘要。下载后再次验证精确长度与 ZIP 摘要、包内独立签名及各身份字段，拒绝源描述和实际包不一致。

下载采用平台 `HttpsURLConnection` 的证书和主机名校验，不安装宽松 TrustManager/HostnameVerifier，不取用 WebView Cookie 或本地 OpenCode 密码。最多跟随 5 次 HTTPS 重定向，拒绝 HTTP 降级、URL 用户凭据、片段、非 200 完整响应和内容重编码；连接/读取各 15 秒超时。包体最多 512 MiB + 64 KiB，检查磁盘余量，以 128 KiB 块写入私有 0600 暂存文件，校验成功后才替换候选 DTO；失败/取消删除本次部分文件，保留旧候选与活动程序。已验证的候选目前保留在私有 cache，进程重建后需重新下载或导入；断点续传和历史缓存管理仍待实现。

用户启动的 `dataSync` 前台服务只处理这一次传输，提供进度通知和取消，使用 30 分钟进度期限及定时局部唤醒锁，并处理 Android 15+ `onTimeout` 立即停止服务。服务不导出、不随开机运行、不用于保持开发会话。依据：[Android 前台服务超时](https://developer.android.com/develop/background-work/services/fgs/timeout)、[HTTPS 连接 API](https://developer.android.com/reference/javax/net/ssl/HttpsURLConnection)。请求 URL、查询参数和远端响应体不进入错误日志。

CI 在同一签名步骤中生成 `.ocaupdate`，再由 `tools/update_feed.py prepare` 写入精确标签的包地址和摘要，使用同一 APK keystore/alias 签名 feed JSON，最后 `frame` 成 `.ocafeed`。签名清单和 Release 草稿均包含该文件；未配置远程仓库、release 环境密钥和实际公开发布前，不算线上更新部署完成。

## v1 格式

`.ocaupdate` 是按以下顺序存放三个 STORED 项的 ZIP：

1. `manifest.json`，最多 16 KiB；签名覆盖其原始 UTF-8 字节。
2. `manifest.sig`，最多 8 KiB；RSA 密钥使用 SHA256withRSA，EC 使用 SHA256withECDSA。
3. `opencode.pack`，gzip 压缩的完整官方 Linux glibc ARM64 单体，包含匹配的 WebUI。

manifest 固定 format=1、kind=opencode-pair、abi=arm64-v8a，记录最低 Host versionCode、官方仓库、40 位提交、版本及压缩/解压内容各自的大小和 SHA-256。生产入口校验当前运行包记录与固定上游锁，使用稳定 JSON 编码，ZIP 时间固定。

签名密钥与 APK 使用相同 keystore/alias。包内不提供可自称可信的公钥，原生验证器从已安装 APK 的签名证书取得公钥。v1 仅支持单个当前 APK signer；密钥轮换与既有活动版本的衔接还需在安装层设计和验证。

APK 另携带内置完整程序的同签名发布者证明。debug 构建用当前配置的 SDK debug keystore 自动生成；release 签名脚本在未签名 APK 中注入证明，再执行 16 KiB zipalign 和 APK 签名。`VerifyCoreProof.java` 用 apksig 验证实际 APK、取其唯一证书验证证明；静态脚本还检查证明与 APK 内完整运行包的大小/摘要/版本/提交一致。应用私有 `.opencode-publisher-proof` 原子记录保留该公共证明，不携带发布者私钥，也不依赖设备 Keystore。

## 原生验证

`SignedOpenCodeUpdate` 采用顺序 ZIP 读取，避免先把任意中央目录或解压内容全部装入内存。它拒绝未知/额外/乱序/压缩 ZIP 项，限制包体、元数据与负载大小；签名通过后才解析版本信息。它严格检查整数、Host 兼容、架构和固定官方仓库，并检查完整 gzip 负载摘要。

暂存时重新验证确认过的 manifest 摘要，限制实际解压字节、校验原始 ELF 摘要，并检查 ARM64、小端 64 位、Debian glibc interpreter、PT_LOAD 文件范围和 16 KiB 对齐。该 API 只向调用方提供的未发布 staging stream 写入；没有修改活动程序或用户数据的能力。

## 安装与恢复

`RuntimeCoordination` 在服务提交后台任务前同步记录所有者，以维护租约排除后端、终端和桌面的启动。独立程序及原始签名证明存放在私有 `opencode-runtimes/<UUID>/`，通过 PRoot 文件 bind 映射为 `/usr/local/bin/opencode`；APK 内置的 Debian 程序不会被本更新器覆盖。正常启动时重新验证活动证明及完整程序 SHA-256。

运行时停止后，按不跟随符号链接的方式复制 `opencode-state`。内部链接保留，指向该状态树以外的链接拒绝自动试运行；瞬态 socket/FIFO 不复制。复制有条目、深度、大小和剩余空间上限，常规文件与日志均 fsync。项目、home、桌面状态和密钥库不参与状态目录切换。

持久日志先记录切换意图，再将原状态目录重命名到私有备份，将副本置为活动状态，并发布新程序选择。新程序在随机 loopback 端口启动，检查实际 `/api/info` 版本、授权成功及匿名 401，随后停止试运行进程，最后记录提交。试运行不向 WebView 发放配对信息。异常或取消会恢复原状态；进程死亡后，下一次明确启动后端/桌面先在后台完成日志恢复。已提交的操作完成提交，未提交的安装恢复旧状态，已确认的回退继续恢复。恢复不会递归删除任何状态或项目目录。

回退会恢复更新前的程序选择与会话；更新后产生的状态保留在 `after-update-state`，失败试运行的状态保留在 `failed-state`。项目文件不回退。连续独立更新可按备份链逐次恢复，并在恢复前检查旧独立程序的签名与摘要。

首次独立更新前，存在发布者证明的内置基线被复制为独立、带证明的保留程序，之后的 Host/rootfs 更新不会让回退依赖原位置的内置文件。rootfs 中已保存且摘要匹配的发布者证明也允许新 Host 识别旧内置程序。仍要求当前 APK signer、最低 Host 与完整摘要验证；密钥轮换及不同真实 Host/schema 的完整迁移矩阵未完成。没有证明的旧基线继续采用原内置摘要条件保护，条件改变时拒绝回退并保留会话/备份。缺失、损坏的旧程序在切换状态前拒绝。未发布暂存和旧快照可能占用空间，历史清理界面仍待实现。

## WebView 版本切换

活动程序 UUID（或 APK 内置程序摘要）参与官方 WebView 的保留键，避免复用上一版本的 DOM。新建官方浏览器先加载原生拦截的随机 `/auth/_opencode_android_resources/` 临时文档；该路径位于当前官方 worker 的 `/auth/` 导航排除规则内。临时文档的 nonce 必须匹配，才使用标准浏览器缓存 API 注销旧 worker、清理 Workbox precache 和本容器的资源版本标记，并清理 HTTP 资源缓存。Cookie、localStorage、IndexedDB 和无关 CacheStorage 不清理。

资源标记存放在该 WebView profile 自己的 CacheStorage，因此测试 profile 与正常 profile 不会互相误认已处理。同一版本的资源缓存保留，标签切换仍保留同一个 WebView。临时文档不进入可恢复导航历史；取消、销毁和渲染进程退出会撤销回调。没有编辑官方 HTML/JS/CSS，也没有给官方页面安装系统 JS bridge。若旧 worker 未遵循已验证的 `/auth/` 排除规则或缓存 API 失败，显示原生重试界面，不加载混合版本。

## 验证记录

2026-10-28 K30（Android 16 / ARM64 / 4 KiB）共 7 项、9.458 秒通过：RSA 和 EC 合成 ELF 的签名/暂存；错误发布者及真实 APK 信任拒绝；被篡改、截断、额外或压缩 ZIP 项；Host 版本、数字类型及架构；错误 ELF 架构/对齐及超出签名声明的解压大小。测试不会执行合成 ELF。

第 7 项使用本机 SDK 的实际 debug 签名身份生成完整官方更新包，在 Android 上以已安装 APK 的证书验证，解压暂存全部 259443704 字节，并通过压缩/解压摘要、长度及实际 glibc ARM64/16 KiB ELF 校验。没有替换活动程序、数据库或项目。证据为 `.cache/evidence/k30-signed-update-verifier-final.txt`；测试暂存和设备传入文件已清理。

后续事务测试覆盖服务/维护互斥、状态目录切换各中断点、已提交恢复、启动失败、回退中断、连续更新/回退、损坏旧程序拒绝、确认摘要绑定、外部符号链接拒绝及 SQLite 原状态保留。合成 ELF 仅用于文件事务测试，从不执行。资源缓存测试在隔离 WebView profile 中验证旧 worker/资源清理，以及 Cookie、localStorage、无关缓存和同版本资源保留。

完整运行包实测使用当前同一官方 dev 版本的完整 APK 签名包，在独立 namespace 中通过真实 PRoot 文件 bind 启动服务，创建并跨更新读取真实测试会话，再回退、逐字节核对原数据库，并重启读取该会话；项目内容保持原样。证据 `.cache/evidence/k30-update-real-runtime-final.txt`，1 项、12.211 秒。**正常用户的数据库从未切换；这也不等于跨不同官方 schema 版本的迁移验收。**

本轮最终覆盖 21 个不同测试：`.cache/evidence/k30-update-render-success.txt` 的 18 项、`.cache/evidence/k30-update-native-controls.txt` 的原生 picker/签名审阅/运行中禁用/取消确认 1 项，以及 `.cache/evidence/k30-update-runtime-terminal-final.txt` 中的完整官方运行包和真实终端 2 项（该轮另重复事务 7 项，共 9 项、23.745 秒通过）。终端打开中、移除后关闭中的临时所有者也会阻止维护，避免取消旧 scope 后漏掉清理。最终生命周期收尾再次验证缓存及渲染 5 项、12.359 秒通过，证据 `.cache/evidence/k30-update-render-delivery.txt`。

原生界面测试只审阅并取消安装，不在正常用户状态上自动确认安装。真实程序切换和数据库恢复由独立 namespace 验收。恢复正常 profile 后，官方可见页面 956 个 DOM 节点，浏览器自身 Cookie 认证 `/api/info` 返回 200；证据 `.cache/evidence/k30-after-transactional-update-normal.json`。其中空闲采样不能作为滚动帧率或百分比性能提升的证明。

交付调试 APK：`artifacts/opencode-android-full-arm64-update-debug.apk`，对应测试 APK 和 `SHA256SUMS-update-arm64.txt` 同目录。旧渲染版制品保留。构建、单元测试、lint 与静态 ARM64/API 29–36/16 KiB 包装检查通过；实测设备仍只有 4 KiB K30。

## 生产命令

```bash
python3 tools/prepare_update.py --output artifacts/opencode-update-manifest.json
# SignUpdate 读取 keystore 密码与 alias 的环境变量，不将私钥写入更新包。
java tools/SignUpdate.java /private/path/release.keystore artifacts/opencode-update-manifest.json artifacts/opencode-update-manifest.sig
python3 tools/package_update.py --manifest artifacts/opencode-update-manifest.json --signature artifacts/opencode-update-manifest.sig --output artifacts/opencode-android-full-arm64.ocaupdate
```

CI 的签名步骤自动串起这些工具。普通构建仅产生待签名输入，发布标签使用 release 环境密钥。测试 keystore 只用于本地验证，没有作为正式发行身份。
