package io.github.opencodeandroid.runtime

import io.github.opencodeandroid.i18n.nativeMessage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.logging.DiagnosticLog
import io.github.opencodeandroid.logging.DiagnosticReader
import io.github.opencodeandroid.logging.DiagnosticSecrets
import kotlinx.coroutines.*
import java.io.File
import android.system.Os
import org.json.JSONObject
import java.util.concurrent.TimeUnit

open class RuntimeService : Service() {
    protected open fun runtimeContext(): android.content.Context = this
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var paths: RuntimePaths
    private var job: Job? = null
    @Volatile private var process: NativeProcess? = null
    private var reconnectJob: Job? = null
    private val bridgeGuard = Any()
    private var destroyed = false
    private var bridgeAcquired = false
    private var hostInitialized = false
    private var diagnosticSecrets: AutoCloseable? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (RuntimeRecovery.pending(paths)) return
            val ownership = RuntimeCoordination.claimService() ?: return
            scope.launch {
                if (paths.installed && !RuntimeRecovery.pending(paths)) runCatching { updateDns() }.onFailure { RuntimeState.log("Could not refresh Debian DNS configuration") }
            }.invokeOnCompletion { ownership.close() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        paths = RuntimePaths(runtimeContext())
        paths.prepare()
        if (!RuntimeCoordination.maintenanceActive) RuntimeState.update("Stopped", false, paths.installed)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("runtime", nativeMessage("本地开发环境"), NotificationManager.IMPORTANCE_LOW))
    }

    private fun initializeHost() {
        paths.prepare(); WorkspaceContext.initialize(runtimeContext())
        synchronized(bridgeGuard) {
            check(!destroyed)
            if (!bridgeAcquired) { io.github.opencodeandroid.bridge.AndroidBridge.acquire(runtimeContext(), "runtime"); bridgeAcquired = true }
        }
        hostInitialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == STOP) {
            stopRuntime()
            return START_NOT_STICKY
        }
        if (action == RECONNECT) {
            val owner = process
            if (owner?.isAlive != true) {
                RuntimeState.paired(null)
                if (job?.isCompleted != false) stopSelf()
                return START_NOT_STICKY
            }
            if (job?.isActive == true && owner?.isAlive == true && RuntimeState.current.value.pairingUrl != null && reconnectJob?.isActive != true) {
                reconnectJob = scope.launch {
                    try {
                        val url = LocalOpenCode(CredentialVault(paths).password()).pairingUrl()
                        if (process === owner && owner.isAlive) RuntimeState.paired(url)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { RuntimeState.log("本地连接恢复失败，请在运行环境重试。") }
                }
            }
            return START_NOT_STICKY
        }
        // Cancellation is followed by asynchronous child/PTY cleanup. Do not start a second owner during it.
        if (job?.isCompleted == false) return START_NOT_STICKY
        var ownership: AutoCloseable = try {
            if (RuntimeRecovery.pending(paths)) RuntimeCoordination.claimMaintenance("正在恢复中断的环境操作")
            else RuntimeCoordination.claimService() ?: error("环境维护中，请稍后重试")
        } catch (_: Exception) { RuntimeState.log("请等待更新维护或当前运行任务结束。"); stopSelf(); return START_NOT_STICKY }
        try {
            synchronized(RuntimeCoordination.lock) {
                if (action in setOf(INSTALL, UPDATE, REPAIR, INSTALL_LANGUAGE_SERVERS)) check(!io.github.opencodeandroid.desktop.DesktopState.current.value.busy) { "请先停止 Debian 桌面，再更新开发环境" }
                RuntimeState.update("Starting", true)
            }
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification())
        } catch (_: Exception) { ownership.close(); RuntimeState.update("Needs attention", false); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            var finalPhase = "Stopped"
            val history = DevelopmentSessionJournal(paths)
            var historyToken: String? = null
            try {
                (ownership as? RuntimeCoordination.MaintenanceLease)?.let { lease -> RuntimeRecovery.recover(paths, lease, RuntimeState::log); ownership = lease.resumeService() }
                val operation = when (action) {
                    START -> DevelopmentOperation.OPENCODE; INSTALL -> DevelopmentOperation.INSTALL; UPDATE -> DevelopmentOperation.UPDATE
                    REPAIR -> DevelopmentOperation.REPAIR; VERIFY -> DevelopmentOperation.VERIFY
                    INSTALL_LANGUAGE_SERVERS -> DevelopmentOperation.LANGUAGE_SERVERS; RECOVER -> DevelopmentOperation.RECOVER
                    else -> error("Unsupported service action")
                }
                try { historyToken = history.begin(DevelopmentKind.RUNTIME, operation, action == START && intent.getBooleanExtra(AUTOMATIC_START, false)) }
                catch (required: DevelopmentRecoveryRequired) { throw required }
                catch (_: Exception) { error("运行记录无法读取或保存，请在运行环境修复记录或释放空间。") }
                DevelopmentSessions.publish(paths)
                initializeHost()
                when (action) {
                    INSTALL -> { RootfsInstaller(this@RuntimeService, paths).install(RuntimeState::log); updateDns() }
                    UPDATE -> { RootfsInstaller(this@RuntimeService, paths).updateBundled(RuntimeState::log); updateDns() }
                    REPAIR -> { RootfsInstaller(this@RuntimeService, paths).repairBundled(RuntimeState::log); updateDns() }
                    VERIFY -> verify()
                    INSTALL_LANGUAGE_SERVERS -> installLanguageServers(intent?.getStringExtra("mirror") ?: "ustc")
                    START -> startOpenCode()
                    RECOVER -> RuntimeState.log("已完成中断操作的恢复，可启动开发环境。")
                    else -> error("Unsupported service action")
                }
            } catch (_: CancellationException) {
            } catch (_: DevelopmentRecoveryRequired) {
                RuntimeState.log("上次运行已中断，终端命令不会自动重放。请在运行环境中明确重新启动。")
                finalPhase = "Interrupted"
            } catch (failure: Exception) {
                // Command environments and provider credentials must never enter logs.
                val detail = failure.message?.takeIf { it.length <= DiagnosticLog.MAX_LINE - 128 } ?: "Diagnostic details omitted"
                RuntimeState.log("${failure.javaClass.simpleName}: $detail")
                finalPhase = "Needs attention"
            } finally {
                try { withContext(NonCancellable) {
                    val child = process
                    child?.destroyForcibly()
                    child?.waitFor(3, TimeUnit.SECONDS)
                    process = null
                    TerminalSessions.stop()
                    RuntimeState.paired(null)
                    if (hostInitialized && !RuntimeRecovery.pending(paths)) {
                        runCatching { File(paths.state, "config/opencode/service-dev.json").delete() }
                            .onFailure { RuntimeState.log("临时连接配置未完成清理；已继续清理进程。") }
                    }
                    historyToken?.let { token -> runCatching { history.finish(DevelopmentKind.RUNTIME, token, finalPhase == "Needs attention") }
                        .onFailure { RuntimeState.log("运行记录未完成保存，重开后按中断处理。") } }
                    DevelopmentSessions.publish(paths)
                    RuntimeState.update(finalPhase, false, paths.installed)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } } finally { diagnosticSecrets?.close(); diagnosticSecrets = null }
            }
        }
        job!!.invokeOnCompletion { ownership.close() }
        return START_NOT_STICKY
    }

    private suspend fun verify() {
        check(paths.installed) { "Install Debian first" }
        RuntimeState.update("Verifying Loader", true)
        RuntimeState.log("Host page size: ${NativeHost.pageSize()} bytes; ABI: ${Build.SUPPORTED_ABIS.first()}; targetSdk: ${applicationInfo.targetSdkVersion}")
        updateDns()
        assets.open("guest/m0-smoke.sh").use { input -> File(paths.config, "m0-smoke.sh").outputStream().use { input.copyTo(it) } }
        val result = runGuest(listOf("/bin/bash", "/android-runtime/m0-smoke.sh"), 120)
        check(result == 0) { "M0 smoke test failed (exit $result). See output." }
        verifyPty()
        RuntimeState.log("M0 core checks passed on THIS device. apt, static ELF and the other page size still require separate acceptance tests.")
    }

    private suspend fun verifyPty() = withContext(Dispatchers.IO) {
        val command = ProotCommand(paths)
        val (fd, pid) = NativeHost.spawnPty(command.arguments(listOf("/bin/bash", "-c", "test -t 0 && test -t 1 && stty size && printf 'PASS pty\\n'")).toTypedArray(),
            command.hostEnvironment().map { "${it.key}=${it.value}" }.toTypedArray(), 24, 80)
        val master = android.os.ParcelFileDescriptor.adoptFd(fd)
        val reader = async {
            try {
                DiagnosticReader.read(android.os.ParcelFileDescriptor.AutoCloseInputStream(master), RuntimeState::log) { ensureActive() }
            } catch (_: java.io.IOException) { /* Linux PTYs end with EIO after the slave closes. */ }
        }
        val waiter = async { NativeHost.waitFor(pid) }
        try {
            val code = withTimeout(30_000) { waiter.await() }
            check(code == 0) { "PTY smoke test failed (exit $code)" }
        } finally {
            NativeHost.signalGroup(pid, 9)
            master.close()
            reader.cancel()
        }
    }

    private suspend fun startOpenCode(): Unit = coroutineScope {
        check(paths.installed) { "Install Debian first" }
        updateDns()
        val version = RootfsInstaller(this@RuntimeService, paths).ensureOpenCode(RuntimeState::log)
        BundledPlugins.install(assets, File(paths.state, "config/opencode"))
        val (selection, selectionGeneration) = WorkspaceContext.launchSelection()
        var directory = selection.directory
        suspend fun prepareStarter() {
            check(runGuest(listOf("/bin/mkdir", "-p", WorkspaceContext.STARTER_DIRECTORY), 10) == 0) { "无法创建 helloworld 工作区，请检查目录或可用空间" }
        }
        if (directory == WorkspaceContext.STARTER_DIRECTORY) prepareStarter()
        if (runGuest(listOf("/usr/bin/test", "-d", directory), 10) != 0) {
            prepareStarter(); directory = WorkspaceContext.STARTER_DIRECTORY
            RuntimeState.log("上次工作区目录已不可用，已打开 helloworld。")
        }
        val password = CredentialVault(paths).password()
        diagnosticSecrets = DiagnosticSecrets.register(password, java.util.Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8)))
        val local = LocalOpenCode(password)
        local.assertPortAvailable()
        // Upstream service mode reads this file and keeps the password out of tool environments.
        // Only the encrypted Keystore copy remains once the process stops.
        val config = File(paths.state, "config/opencode/service-dev.json")
        check(config.parentFile!!.isDirectory || config.parentFile!!.mkdirs())
        config.writeText(JSONObject().put("hostname", "127.0.0.1").put("port", LocalOpenCode.PORT).put("password", password).toString())
        Os.chmod(config.path, 384)
        val child = ProotCommand(paths, directory = directory).process(listOf("/usr/local/bin/opencode", "serve", "--service", "--hostname", "127.0.0.1", "--port", LocalOpenCode.PORT.toString()),
            mapOf("OPENCODE_DISABLE_AUTOUPDATE" to "1")).start()
        process = child
        child.outputStream.close()
        val reader = launch {
            try {
                DiagnosticReader.read(child.inputStream, RuntimeState::log) { ensureActive() }
            } catch (_: java.io.IOException) { if (isActive && child.isAlive) RuntimeState.log("OpenCode output stream disconnected") }
        }
        try {
            RuntimeState.update("Starting OpenCode", true)
            val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
            while (true) {
                check(child.isAlive) { "OpenCode exited before becoming ready (exit ${child.exitValue()})" }
                val ready = try { local.ready(version) } catch (_: java.io.IOException) { false }
                if (ready) break
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "OpenCode readiness timed out" }
                delay(500)
            }
            // Service mode has its own daemon HOME. Restore the user's official session location
            // and route explicitly, so its workspace does not fall back to that daemon directory.
            runCatching { local.registerDirectory(directory) }
                .onFailure { RuntimeState.log("默认项目尚未就绪，可从文件页重试。") }
            val previous = selection.session?.takeIf { selection.directory == directory }
                ?.takeIf { runCatching { local.sessionDirectory(it) == directory }.getOrDefault(false) }
            val workspaceUrl = previous?.let(WorkspaceContext::sessionUrl) ?: local.createSession(directory)
            WorkspaceContext.rememberSessionIfCurrent(directory, checkNotNull(WorkspaceContext.localSession(workspaceUrl)), selectionGeneration)
            RuntimeState.workspace(workspaceUrl)
            TerminalSessions.start(paths)
            RuntimeState.paired(local.pairingUrl())
            RuntimeState.update("OpenCode running", true)
            RuntimeState.log("Official OpenCode $version ready. Local authentication verified.")
            while (isActive && child.isAlive) delay(500)
            reader.join()
            error("OpenCode exited (exit ${child.exitValue()})")
        } finally {
            child.destroyForcibly()
            child.waitFor(3, TimeUnit.SECONDS)
            process = null; reader.cancel()
        }
    }

    private suspend fun runGuest(command: List<String>, timeoutSeconds: Long): Int = coroutineScope {
        val child = ProotCommand(paths).process(command).start()
        process = child
        child.outputStream.close()
        val reader = launch {
            try { DiagnosticReader.read(child.inputStream, RuntimeState::log) { ensureActive() } }
            catch (_: java.io.IOException) { if (isActive && child.isAlive) RuntimeState.log("Guest output stream disconnected") }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutSeconds * 1000
        try {
            while (isActive && child.isAlive) {
                delay(100)
                check(android.os.SystemClock.elapsedRealtime() < deadline) { "Guest command timed out" }
            }
            reader.join()
            child.exitValue()
        } finally {
            child.destroyForcibly()
            child.waitFor(3, TimeUnit.SECONDS)
            process = null
        }
    }

    private suspend fun installLanguageServers(mirror: String) {
        check(paths.installed) { "Install Debian first" }
        check(mirror in setOf("ustc", "official"))
        updateDns()
        RuntimeState.update("安装语言服务", true)
        val script = File(paths.config, "install-language-servers.sh")
        check(!java.nio.file.Files.isSymbolicLink(script.toPath()))
        assets.open("guest/install-language-servers.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
        val result = runGuest(listOf("/bin/bash", "/android-runtime/install-language-servers.sh", mirror), 1200)
        check(result == 0) { "Language server installation failed (exit $result). See output." }
        RuntimeState.log("C/C++ 和 Python 语言服务已就绪，启动 OpenCode 后可使用 LSP。")
    }

    private fun updateDns() {
        if (!GuestNetwork.configure(this, paths)) {
            RuntimeState.log("No active network. Local services remain available; Debian DNS will refresh when a network connects.")
            return
        }
    }

    private fun notification(): Notification {
        val returnToApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.OPEN_WORKSPACE), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "runtime").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("OpenCode Android Full").setContentText(nativeMessage("本地 Debian 开发会话"))
            .setOngoing(true).setContentIntent(returnToApp).addAction(0, nativeMessage("停止"), stop).build()
    }

    private fun stopRuntime() {
        RuntimeState.update("Stopping", true)
        job?.cancel(); process?.destroyForcibly()
        if (job?.isCompleted != false) { RuntimeState.update("Stopped", false, paths.installed); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onDestroy() {
        synchronized(bridgeGuard) { destroyed = true; if (bridgeAcquired) { io.github.opencodeandroid.bridge.AndroidBridge.release("runtime"); bridgeAcquired = false } }
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        process?.destroyForcibly(); TerminalSessions.stop(); scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val INSTALL = "io.github.opencodeandroid.INSTALL"
        const val UPDATE = "io.github.opencodeandroid.UPDATE"
        const val REPAIR = "io.github.opencodeandroid.REPAIR"
        const val VERIFY = "io.github.opencodeandroid.VERIFY"
        const val INSTALL_LANGUAGE_SERVERS = "io.github.opencodeandroid.INSTALL_LANGUAGE_SERVERS"
        const val START = "io.github.opencodeandroid.START"
        const val AUTOMATIC_START = "io.github.opencodeandroid.AUTOMATIC_START"
        const val RECONNECT = "io.github.opencodeandroid.RECONNECT"
        const val RECOVER = "io.github.opencodeandroid.RECOVER"
        const val STOP = "io.github.opencodeandroid.STOP"
    }
}
