package io.github.opencodeandroid.desktop

import io.github.opencodeandroid.i18n.nativeMessage

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.logging.DiagnosticLog
import io.github.opencodeandroid.logging.DiagnosticReader
import io.github.opencodeandroid.logging.DiagnosticSecrets
import io.github.opencodeandroid.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

class DesktopService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var paths: RuntimePaths
    private var job: Job? = null
    @Volatile private var process: NativeProcess? = null
    private var activeAction: String? = null
    private val launchers = java.util.Collections.synchronizedSet(mutableSetOf<NativeProcess>())
    private val launcherSlots = java.util.concurrent.Semaphore(4)
    private val bridgeGuard = Any()
    private var destroyed = false
    private var bridgeAcquired = false
    private var hostInitialized = false
    private var diagnosticSecrets: AutoCloseable? = null

    override fun onCreate() {
        super.onCreate(); paths = RuntimePaths(this); paths.prepare(); DesktopState.refresh(paths)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("desktop", nativeMessage("Debian 桌面"), NotificationManager.IMPORTANCE_LOW))
    }

    private fun initializeHost() {
        paths.prepare(); WorkspaceContext.initialize(this)
        synchronized(bridgeGuard) {
            check(!destroyed)
            if (!bridgeAcquired) { io.github.opencodeandroid.bridge.AndroidBridge.acquire(this, "desktop"); bridgeAcquired = true }
        }
        hostInitialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == OPEN_PROJECT) {
            if (DesktopState.current.value.connected && process?.isAlive == true && launcherSlots.tryAcquire()) {
                val ownership = RuntimeCoordination.claimService()
                if (ownership == null) { launcherSlots.release(); return START_NOT_STICKY }
                val directory = WorkspaceContext.directory.value
                scope.launch {
                    var child: NativeProcess? = null
                    try {
                        ensureActive()
                        val running = ProotCommand(paths, 1000, directory).process(listOf("/bin/bash", "/android-runtime/desktop-open-project.sh", directory)).start()
                        child = running; launchers.add(running); ensureActive()
                        running.inputStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) ensureActive() }
                        running.waitFor(3, TimeUnit.SECONDS)
                        if (running.isAlive || running.exitValue() != 0) DesktopState.log("项目文件夹未能打开，请检查目录是否存在。")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DesktopState.log("项目文件夹未能打开，请检查目录是否存在。") }
                    finally { child?.let { it.destroyForcibly(); it.waitFor(3, TimeUnit.SECONDS); launchers.remove(it) } }
                }.invokeOnCompletion { ownership.close(); launcherSlots.release() }
            } else if (job?.isCompleted != false) stopSelf()
            return START_NOT_STICKY
        }
        if (action == STOP) {
            synchronized(launchers) { launchers.forEach { it.destroyForcibly() } }
            val running = job
            if (activeAction == START && process != null && running?.isActive == true) {
                File(paths.desktop, "stop-request").writeText("stop")
                scope.launch { withTimeoutOrNull(5000) { running.join() }; if (running.isActive) { running.cancel(); process?.destroyForcibly() } }
            } else { running?.cancel(); process?.destroyForcibly() }
            if (running?.isCompleted != false) stopSelf()
            return START_NOT_STICKY
        }
        // Cancellation still owns its process and cleanup until the job has completed.
        if (job?.isCompleted == false) return START_NOT_STICKY
        var ownership: AutoCloseable = try {
            if (RuntimeRecovery.pending(paths)) RuntimeCoordination.claimMaintenance("正在恢复中断的环境操作")
            else RuntimeCoordination.claimService() ?: error("环境维护中")
        } catch (_: Exception) { DesktopState.log("请等待更新维护或当前运行任务结束。"); stopSelf(); return START_NOT_STICKY }
        try {
            synchronized(RuntimeCoordination.lock) {
                check(!RuntimeState.current.value.busy || RuntimeState.current.value.pairingUrl != null) { "请等待当前开发环境任务结束" }
                DesktopState.update(if (action == INSTALL) "正在安装桌面" else "正在启动桌面", true)
            }
            if (Build.VERSION.SDK_INT >= 34) startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(2, notification())
        } catch (_: Exception) { ownership.close(); DesktopState.update("需要处理", false); stopSelf(); return START_NOT_STICKY }
        val mirror = intent.getStringExtra("mirror") ?: "ustc"
        val resolution = intent.getStringExtra("resolution") ?: "1280x720"
        activeAction = action
        job = scope.launch {
            var phase = "已停止"
            val history = io.github.opencodeandroid.runtime.DevelopmentSessionJournal(paths)
            var historyToken: String? = null
            try {
                (ownership as? RuntimeCoordination.MaintenanceLease)?.let { lease -> RuntimeRecovery.recover(paths, lease, DesktopState::log); ownership = lease.resumeService() }
                try { historyToken = history.begin(io.github.opencodeandroid.runtime.DevelopmentKind.DESKTOP,
                    if (action == INSTALL) io.github.opencodeandroid.runtime.DevelopmentOperation.DESKTOP_INSTALL else io.github.opencodeandroid.runtime.DevelopmentOperation.DESKTOP) }
                catch (_: Exception) { error("运行记录无法读取或保存，请在运行环境修复记录或释放空间。") }
                io.github.opencodeandroid.runtime.DevelopmentSessions.publish(paths)
                initializeHost()
                check(paths.installed) { "请先安装 Debian 开发环境" }
                for (name in listOf("desktop-install.sh", "desktop-session.sh", "desktop-open-project.sh", "desktop-gateway.py", "desktop.html", "chromium-launch.sh")) assets.open("guest/$name").use { input ->
                    java.nio.channels.Channels.newOutputStream(java.nio.file.Files.newByteChannel(File(paths.config, name).toPath(), setOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS))).use { input.copyTo(it) }
                }
                if (action == INSTALL) {
                    check(GuestNetwork.configure(this@DesktopService, paths)) { "请先连接网络" }
                    execute(listOf("/bin/bash", "/android-runtime/desktop-install.sh", mirror), 2400)
                    DesktopState.refresh(paths)
                    check(DesktopState.available(paths)) { "桌面组件安装不完整，请重试安装" }
                    DesktopState.log("桌面已安装，可按需启动。")
                } else {
                    check(action == START && DesktopState.available(paths)) { "请先安装桌面组件" }
                    require(resolution in setOf("720x1280", "1024x768", "1280x720", "1600x900"))
                    execute(listOf("/bin/bash", "-c", "getent passwd 1000 >/dev/null || useradd --uid 1000 --user-group --home-dir /root --no-create-home --shell /bin/bash developer"), 30)
                    listOf(5901, 6080).forEach { port -> ServerSocket().use { it.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)) } }
                    clearStaleDisplay()
                    File(paths.desktop, "xfce-ready").delete()
                    val private = File(paths.desktop, "private").apply { mkdirs(); Os.chmod(path, 448) }
                    val password = CredentialVault(paths, "desktop").password()
                    diagnosticSecrets = DiagnosticSecrets.register(password, password.take(8), DesktopState.socketToken(password),
                        java.util.Base64.getEncoder().encodeToString("desktop:$password".toByteArray(Charsets.UTF_8)))
                    File(private, "gateway.json").apply { writeText(JSONObject().put("password", password).put("websocket", DesktopState.socketToken(password)).toString()); Os.chmod(path, 384) }
                    File(private, "vnc-input").apply { writeText(password.take(8) + "\n"); Os.chmod(path, 384) }
                    val directory = WorkspaceContext.directory.value
                    execute(listOf("/bin/bash", "/android-runtime/desktop-session.sh", resolution, directory), null, password, identity = 1000, directory = directory)
                }
            } catch (_: CancellationException) { }
            catch (failure: Exception) {
                val detail = failure.message?.takeIf { it.length <= DiagnosticLog.MAX_LINE - 128 } ?: "桌面操作失败，详细信息过长或不可用。"
                DesktopState.log("${failure.javaClass.simpleName}: $detail"); phase = "需要处理"
            }
            finally {
                try { withContext(NonCancellable) {
                    process?.destroyForcibly(); process?.waitFor(3, TimeUnit.SECONDS); process = null
                    synchronized(launchers) { launchers.forEach { it.destroyForcibly() } }
                    if (hostInitialized && !RuntimeRecovery.pending(paths)) {
                        runCatching {
                            File(paths.desktop, "run/dbus-address").delete()
                            File(paths.desktop, "private/gateway.json").delete(); File(paths.desktop, "private/vnc-input").delete(); File(paths.desktop, "private/vnc.passwd").delete()
                            File(paths.desktop, "xfce-ready").delete(); File(paths.desktop, "stop-request").delete()
                        }.onFailure { DesktopState.log("停止标记未完成保存；已继续清理进程。") }
                    }
                    historyToken?.let { token -> runCatching { history.finish(io.github.opencodeandroid.runtime.DevelopmentKind.DESKTOP, token, phase == "需要处理") }
                        .onFailure { DesktopState.log("运行记录未完成保存，重开后按中断处理。") } }
                    io.github.opencodeandroid.runtime.DevelopmentSessions.publish(paths)
                    DesktopState.refresh(paths); DesktopState.update(phase, false)
                    // STOP has its own startId; the original START id no longer stops this service.
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } } finally { diagnosticSecrets?.close(); diagnosticSecrets = null }
            }
        }
        job!!.invokeOnCompletion { ownership.close() }
        return START_NOT_STICKY
    }

    private suspend fun execute(command: List<String>, timeout: Long?, password: String? = null, identity: Int = 0, directory: String = WorkspaceContext.DEFAULT_DIRECTORY) = coroutineScope {
        val child = ProotCommand(paths, identity, directory).process(command).start(); process = child
        val reader = launch {
            try { DiagnosticReader.read(child.inputStream, DesktopState::log) { ensureActive() } }
            catch (_: java.io.IOException) { }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + (timeout ?: 120) * 1000
        var ready = false
        try {
            while (isActive && child.isAlive) {
                if (password != null && !ready && File(paths.desktop, "xfce-ready").isFile && gatewayReady(password)) {
                    ready = true; DesktopState.update("桌面运行中", true, true)
                }
                if (timeout != null || !ready) check(android.os.SystemClock.elapsedRealtime() < deadline) { "桌面操作超时，可查看记录后重试" }
                delay(500)
            }
            reader.join(); check(child.exitValue() == 0) { "桌面进程退出（${child.exitValue()}），请查看运行记录" }
        } finally {
            child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS); process = null; reader.cancel()
        }
    }

    private fun clearStaleDisplay() {
        // X11 runtime nodes are disposable. Never remove a live display or follow links.
        val lock = File(paths.temporary, ".X1-lock")
        if (!lock.exists() || java.nio.file.Files.isSymbolicLink(lock.toPath())) return
        val attributes = Os.lstat(lock.path)
        if (attributes.st_uid != android.os.Process.myUid() || !OsConstants.S_ISREG(attributes.st_mode)) return
        val pid = lock.readText().trim().toIntOrNull()?.takeIf { it > 1 } ?: return
        val dead = try { Os.kill(pid, 0); false } catch (failure: android.system.ErrnoException) { failure.errno == OsConstants.ESRCH }
        if (!dead) return
        val directory = File(paths.temporary, ".X11-unix")
        if (java.nio.file.Files.isSymbolicLink(directory.toPath())) return
        val socket = File(directory, "X1")
        val metadata = runCatching { Os.lstat(socket.path) }.getOrNull()
        if (metadata != null && (metadata.st_uid != android.os.Process.myUid() || !OsConstants.S_ISSOCK(metadata.st_mode))) return
        lock.delete(); if (metadata != null) socket.delete()
    }

    private fun gatewayReady(password: String): Boolean = runCatching {
        fun response(authorized: Boolean): Int {
            val connection = LoopbackHttp.open(URL("http://127.0.0.1:6080/"))
            try {
                connection.connectTimeout = 1000; connection.readTimeout = 1000; connection.instanceFollowRedirects = false
                if (authorized) connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("desktop:$password".toByteArray(), android.util.Base64.NO_WRAP))
                return connection.responseCode
            } finally { connection.disconnect() }
        }
        response(false) == 401 && response(true) == 200
    }.getOrDefault(false)

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, DesktopService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "desktop").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(nativeMessage("Debian 桌面")).setContentText(nativeMessage("本地图形工作区")).setOngoing(true).setContentIntent(open).addAction(0, nativeMessage("停止桌面"), stop).build()
    }
    override fun onDestroy() {
        synchronized(bridgeGuard) { destroyed = true; if (bridgeAcquired) { io.github.opencodeandroid.bridge.AndroidBridge.release("desktop"); bridgeAcquired = false } }
        process?.destroyForcibly(); synchronized(launchers) { launchers.forEach { it.destroyForcibly() } }; scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val INSTALL = "io.github.opencodeandroid.DESKTOP_INSTALL"
        const val START = "io.github.opencodeandroid.DESKTOP_START"
        const val STOP = "io.github.opencodeandroid.DESKTOP_STOP"
        const val OPEN_PROJECT = "io.github.opencodeandroid.DESKTOP_OPEN_PROJECT"
    }
}
