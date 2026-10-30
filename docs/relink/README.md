# Modified runtime proof / 修改运行库实测

On 2026-10-30, modified WebKit → locally rebuilt Bun → the pinned official OpenCode → the installed Android APK's read-only PRoot loader passed on a K30, Android 16, ordinary application UID, 4 KiB pages. The same rebuilt executable also completed a real public GLM Agent task: writing a four-file C project, building an AArch64 test executable and passing independent compiler/runtime assertions. See [machine-readable scope and hashes](validation.json) and [actual Android/QEMU output](output.txt).

2026-10-30 已在 K30 / Android 16 / 普通应用 UID / 4 KiB 页设备实测以上链路。同一重编译程序还通过真实 GLM 云模型 Agent 测试：创建四文件 C 工程、编译 ARM64 测试程序，并通过独立断言。测试使用全新 Debian、独立 HOME/config/data/state 与合成项目，没有复制私人模型凭据。APK 内置程序也单独通过了真实 GLM 工程测试。

This is a developer-built Linux program executed through the existing APK loader in a fresh test profile. It is not a replacement of the published APK or its managed update slot. The official frontend source is unchanged; the rebuilt embedded frontend archive is byte-identical to the original archive. The checked-in source-lock evidence flag refers only to this exact pinned input and device scope. Full release/license review and the remaining device/lifecycle/account matrix are still open.

这是现有 APK Loader 在独立测试环境执行本地开发程序的证明，没有替换已分发 APK 或正常用户的受管程序槽。官方前端源码未改，嵌入前端归档与原版逐字节一致。源码锁中的通过标记仅对应本记录固定输入及设备范围；完整发行/许可证审查及其余设备、生命周期、账号矩阵仍待完成。

## Source and build procedure / 源码与构建过程

Use a new build directory. Obtain and verify the five immutable archives in the [build-source supplement](https://github.com/B5-Software/opencode-android-full/releases/download/v0.1.0-beta.1/upstream-build-sources-v0.1.0-beta.1.zip), including its `VERIFY.py`. The pinned source identities are in [the lock](../../runtime/upstream-source-lock.json). Keep the source links and upstream build recipes when extracting.

在新目录展开补充包并执行自带校验器，保留链接和上游构建配方。下面是此次实测构建步骤，不承诺不同宿主生成相同二进制摘要。

1. Expand Bun to `PROOF/bun`, WebKit to `PROOF/WebKit`, plus the pinned TinyCC and ICU sources. Apply [bun-webkit-arm64.patch](bun-webkit-arm64.patch) from `PROOF` with `patch -p1`. Point `bun/vendor/WebKit` at that WebKit tree. The patch adds one VM constructor diagnostic and retains the GNU ARM64/sysroot/static-ICU settings that upstream local mode otherwise overrides.
2. Prepare the pinned Bun build dependencies. This run used LLVM 23.1.1, Rust `nightly-2026-09-15` with `aarch64-unknown-linux-gnu`, CMake 3.30.5 and the upstream-described ARM64 GNU sysroot/GCC inputs. Their source-pinned/published hashes were checked. A signed Ubuntu host supplied Ruby, Bison, Gawk, Gperf, libxml2 and libedit development tools. No Docker daemon was started.
3. Build ICU 78.3 host tools out of tree, then a separate ARM64 static build with `--host=aarch64-unknown-linux-gnu --with-cross-build=<host-build> --enable-static --disable-shared --disable-layoutex --disable-layout --with-data-packaging=static --disable-samples --disable-debug --disable-tests --disable-extras --disable-icuio --prefix=<arm64-prefix>`. Use LLVM target/sysroot flags, `-Os`, `U_STATIC_IMPLEMENTATION`, and the WebKit-supplied `icu/udata-decompress-hook.patch` on the target source. The host tools remain native.
4. Follow the shipped WebKit ICU data filtering/compression recipe. This run removed 315 items, kept 3,990, and retained reachable `ja`, `zh`, `zh_Hant` RBNF resources. Use the host `icupkg` and Node 26.3.0 to run `WebKit/icu/compress-data.ts <filtered.dat> <arm64-prefix>/lib/libicudata.a --skip WebKit/icu/keep-raw.txt --icupkg <host-build>/bin/icupkg --cc 'clang --target=aarch64-linux-gnu --sysroot=<sysroot>' --ar llvm-ar`, with Zstd 1.5.7 on PATH. The target object's architecture and runtime calendars were checked.
5. Put the matching LLVM/CMake/native Bun tools on PATH, and set `LINUX_GLIBC_SYSROOT`, `OPENCODE_RELINK_ICU_ROOT` and `OPENCODE_RELINK_ZSTD_SOURCE_DIR` to those private build paths. Use task-owned Cargo/Rustup homes. From the Bun tree run:

   ```sh
   bun scripts/build.ts --profile=release-local --os=linux --arch=aarch64 --abi=gnu -j2
   qemu-aarch64-static -L "$LINUX_GLIBC_SYSROOT" build/release/bun -e 'console.log(Bun.version, Bun.revision); console.log(new Intl.DateTimeFormat("ja-JP-u-ca-japanese",{year:"numeric",timeZone:"UTC"}).format(new Date("2020-02-01T00:00:00Z"))); console.log(new Intl.DateTimeFormat("zh-Hant-u-ca-chinese",{dateStyle:"full",timeZone:"UTC"}).format(new Date("2020-02-01T00:00:00Z")))'
   ```

   The final source build completed all 1,554 build steps. Official Cargo index timeouts were resolved with a task-local sparse mirror, retaining the original lockfile checksums and TLS checks. A native ARM64 build can instead follow the same pinned upstream local-build instructions with its own matching dependencies.

6. In a separate copy of the pinned OpenCode source, apply [opencode-bun-template.patch](opencode-bun-template.patch), install its locked dependencies, and select your ARM64 Bun as the compile template:

   ```sh
   export OPENCODE_RELINK_BUN_TEMPLATE=/absolute/path/to/modified/bun
   export OPENCODE_TARGET=linux-arm64 OPENCODE_CHANNEL=dev
   export OPENCODE_VERSION=2.1.4-dev.f993a89d13c7 VITE_SENTRY_DSN=
   export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
   bun run --cwd packages/cli script/build.ts --skip-install --outdir=/absolute/path/to/new/dedicated/output
   ```

   Use the same clean build environment as `tools/build_opencode.py`: no inherited Sentry upload/OTLP/test fixture or model snapshot overrides. Upstream recursively clears `--outdir`, so use a new dedicated build directory. **Do not pass `--skip-web-ui`: that flag generates an empty archive.** The successful archive here was 25,791,333 bytes with the same SHA-256 as the original official frontend archive. A first headless attempt was rejected by the Android frontend test and rebuilt.

7. `tools/check_elf.py <binary>` checks the resulting ELF. `tools/bundle_opencode.py arm64-v8a <binary>` is the separate route for constructing your own APK with your own signing identity and matching publisher proof. This recorded device proof uses the developer-program route below; it did not exercise a replacement formally signed APK.

The upstream full `bun run check` passed lint and 28 package checks, then encountered Windows' plain-file representation of `packages/app/src/custom-elements.d.ts`, an original Git symlink. Creating the native symlink was denied by the host (1314); its original bytes were restored. The affected CLI's official `bun run typecheck:tsc` passed. This is not a full green upstream monorepo type-check claim, and no frontend source workaround was applied.

## Device reproduction / 真机复验

Build `assembleDebugAndroidTest` against a compatible development APK. Both tests below are opt-in; CI does not silently send model requests. Stage only your compiled program in this application's own external files directory, using its SHA-256 in the leaf name. The test copies it into its private fresh runtime and verifies the copied bytes before execution. It does not disable component signature verification.

先构建并安装匹配开发版的测试 APK，仅把本地重编译程序放入该应用自身的外部文件目录；测试再复制到独立环境、检查摘要并执行。将下面的序列号、摘要和文件路径替换为自己实际值。免费模型的可用性可能变化，测试明确限制于列出的官方免费模型，不会复制现有账号配置。

```sh
ADB=adb
DEVICE=your-device-serial
SHA=your-64-character-lowercase-sha256
PROGRAM=/absolute/path/to/your/opencode
$ADB -s "$DEVICE" shell mkdir -p /sdcard/Android/data/io.github.opencodeandroid/files/relink-proof
$ADB -s "$DEVICE" push "$PROGRAM" "/sdcard/Android/data/io.github.opencodeandroid/files/relink-proof/opencode-${SHA:0:12}"
$ADB -s "$DEVICE" shell am instrument -w -r \
  -e class io.github.opencodeandroid.runtime.RelinkRuntimeAcceptanceTest \
  -e isolatedBrowser true -e includeRelinkRuntime true -e relinkSha256 "$SHA" \
  io.github.opencodeandroid.test/io.github.opencodeandroid.ui.IsolatedWebTestRunner
$ADB -s "$DEVICE" shell am instrument -w -r \
  -e class io.github.opencodeandroid.runtime.PublicCloudAgentAcceptanceTest \
  -e isolatedBrowser true -e includePublicCloudModel true -e publicCloudRuntimeSha256 "$SHA" \
  io.github.opencodeandroid.test/io.github.opencodeandroid.ui.IsolatedWebTestRunner
```

Omit `publicCloudRuntimeSha256` to test the APK-pinned program instead. After all tests, remove only the staged `relink-proof/opencode-<hash-prefix>` file you created. Test profiles clean up their own cache directories and preference names; they retain this shared input for subsequent tests. Android instrumentation's final `INSTRUMENTATION_CODE: -1` is normal; inspect the JUnit result for actual success/failures. The recorded passing runs were 79.848 seconds for the relink API/frontend/database proof and 103.221 seconds for real GLM inference with that same rebuilt executable.
