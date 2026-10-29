package io.github.opencodeandroid.diagnostics

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import io.github.opencodeandroid.backup.ArchiveTraversal
import io.github.opencodeandroid.diagnostics.protocol.DiagnosticsProtocol as Protocol
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.runtime.update.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Finite debug-only scenario. The separately instrumented controller cannot send paths or commands. */
class BackgroundDiagnosticsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    @Volatile private var activeId: String? = null
    @Volatile private var response = "{}"
    @Volatile private var lastPoll = SystemClock.elapsedRealtime()
    private val resultFile get() = File(cacheDir, "background-diagnostics-result.json")
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (packageManager.checkSignatures(Process.myUid(), message.sendingUid) != PackageManager.SIGNATURE_MATCH) return
            if (message.data.getInt("version") != Protocol.VERSION) return
            val data = if (message.what == Protocol.HELLO) JSONObject().put("version", Protocol.VERSION).put("uid", Process.myUid())
                .put("pid", Process.myPid()).put("package", packageName).put("active", worker != null).toString()
            else {
                if (message.data.getString("id") != activeId) return
                lastPoll = SystemClock.elapsedRealtime()
                if (message.what == Protocol.CANCEL) worker?.cancel()
                if (message.what !in listOf(Protocol.STATUS, Protocol.CANCEL)) return
                response
            }
            runCatching { message.replyTo?.send(Message.obtain(null, message.what).apply { this.data = Bundle().apply { putString("json", data) } }) }
        }
    })
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "隔离后台验收", NotificationManager.IMPORTANCE_LOW))
        runCatching { if (UpdateFiles.exists(resultFile)) {
            UpdateFiles.regular(resultFile); check(resultFile.length() <= 65536)
            response = resultFile.readText(); activeId = JSONObject(response).getString("id")
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL_ACTION) { if (intent.getStringExtra("id") == activeId) worker?.cancel(); if (worker == null) stopSelf(); return START_NOT_STICKY }
        if (intent?.action != Protocol.START || worker != null) { if (worker == null) stopSelf(); return START_NOT_STICKY }
        val id = intent.getStringExtra("id")?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
        if (id == null) { stopSelf(); return START_NOT_STICKY }
        activeId = id; lastPoll = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification(id), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, notification(id))
        val report = JSONObject().put("version", 1).put("id", id).put("targetUid", Process.myUid()).put("finished", false).put("passed", false).put("phase", "preparing")
        publish(report)
        var entered = false
        val task = scope.launch(start = CoroutineStart.LAZY) {
            entered = true
            var root: File? = null
            try {
                withTimeout(14 * 60_000L) {
                    requireNoUserSession()
                    root = File(cacheDir.canonicalFile, "update-lifecycle-$id").apply { check(mkdir()) }
                    val files = File(root, "files").apply { check(mkdir()) }
                    val paths = RuntimePaths(object : ContextWrapper(this@BackgroundDiagnosticsService) { override fun getFilesDir() = files })
                    val config = File(cacheDir, "lifecycle-probe-profile.json")
                    if (UpdateFiles.exists(config)) UpdateFiles.regular(config)
                    UpdateFiles.json(config, JSONObject().put("id", id))
                    report.put("phase", "installing-private-debian"); publish(report)
                    startProbe(RuntimeService.INSTALL)
                    await(180000) { status(root!!)?.let { it.getBoolean("ready") && !it.getBoolean("busy") } == true }
                    File(paths.workspace, "soak.c").writeText(C_PROGRAM)
                    startProbe(RuntimeService.START)
                    await(90000) { status(root!!)?.getBoolean("paired") == true }
                    val metadata = PublishedOpenCode(paths).let { publisher -> publisher.metadata(checkNotNull(publisher.stored() ?: publisher.bundled())) }
                    val client = LocalOpenCode(CredentialVault(paths).password())
                    val session = client.createSession("/workspace").substringAfterLast('/')
                    val samples = JSONArray(); val began = SystemClock.elapsedRealtime()
                    var opened = false; var checked = 0
                    var firstIdleFd: Int? = null; var lastIdleFd: Int? = null
                    while (SystemClock.elapsedRealtime() - began < 600000L) {
                        ensureActive(); check(SystemClock.elapsedRealtime() - lastPoll < 60000) { "controller-disconnected" }
                        val elapsed = SystemClock.elapsedRealtime() - began
                        val phase = if (elapsed < 60000) "baseline" else if (elapsed < 240000) "eight-pty-compiler-memory-load" else "idle-with-eight-terminals"
                        if (elapsed >= 60000 && !opened) {
                            for (slot in 1..8) startProbe(LifecycleProbeService.SOAK_TERMINAL, slot)
                            opened = true
                            await(20000) { status(root!!)?.getJSONArray("terminals")?.length() == 8 }
                        }
                        check(client.ready(metadata.version)); check(client.sessionDirectory(session) == "/workspace"); checked++
                        val before = status(root!!)?.optLong("sampleTime") ?: -1
                        startProbe(LifecycleProbeService.SAMPLE)
                        await(5000) { (status(root!!)?.optLong("sampleTime") ?: -1) > before }
                        val current = checkNotNull(status(root!!))
                        check(current.getInt("uid") == Process.myUid() && current.getBoolean("paired"))
                        check(current.getInt("importance") >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) { "unexpected-foreground-priority" }
                        if (opened) {
                            check(current.getJSONArray("terminals").length() == 8)
                            val exits = current.getJSONArray("exits"); check((0 until exits.length()).all(exits::isNull)) { "unexpected-shell-exit" }
                        }
                        val sample = JSONObject(current.toString()).put("elapsedMs", elapsed).put("phase", phase)
                        sample.put("workstation", WorkstationProcessMetrics.sample(this@BackgroundDiagnosticsService, current))
                        samples.put(sample)
                        if (elapsed >= 300000 && !current.isNull("fdCount")) {
                            if (firstIdleFd == null) firstIdleFd = current.getInt("fdCount")
                            lastIdleFd = current.getInt("fdCount")
                        }
                        report.put("phase", phase).put("elapsedMs", elapsed).put("samples", samples).put("authenticatedChecks", checked)
                        publish(report)
                        delay(15000)
                    }
                    for (slot in 1..8) {
                        check(File(paths.workspace, "done-$slot").readText() == "done")
                        check(File(paths.workspace, "output-$slot").readLines() == List(6) { "memory-ok" }) { "compiler-or-memory-worker-failed" }
                    }
                    check(client.sessionDirectory(session) == "/workspace")
                    check(firstIdleFd != null && lastIdleFd != null && lastIdleFd!! <= firstIdleFd!! + 4) { "host-fd-growth" }
                    report.put("firstIdleFd", firstIdleFd).put("lastIdleFd", lastIdleFd).put("completedCompiles", 48)
                        .put("memoryWorkers", 8).put("memoryBytesPerWorker", 16 * 1024 * 1024).put("passed", true)
                }
            } catch (_: CancellationException) { report.put("error", "cancelled-or-timeout") }
            catch (failure: Exception) { report.put("error", failure.javaClass.simpleName + ":" + (failure.message?.take(120) ?: "scenario-failed")) }
            finally {
                withContext(NonCancellable) {
                    try { root?.let { cleanup(it, id) }; report.put("cleanup", true) }
                    catch (_: Exception) { report.put("cleanup", false).put("passed", false).put("cleanupError", "owned-fixture-not-removed") }
                    report.put("finished", true); publish(report)
                    runCatching { UpdateFiles.json(resultFile, report) }
                    withContext(Dispatchers.Main.immediate) { worker = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
        worker = task
        task.invokeOnCompletion { if (!entered) Handler(mainLooper).post {
            report.put("finished", true).put("error", "cancelled-before-start"); publish(report)
            worker = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        } }
        task.start(); return START_NOT_STICKY
    }
    private fun publish(report: JSONObject) { val bytes = report.toString(); check(bytes.toByteArray().size <= 65536); response = bytes }
    @Suppress("DEPRECATION")
    private fun requireNoUserSession() {
        val manager = getSystemService(ActivityManager::class.java)
        check(manager.getRunningServices(50).none { it.service.className in setOf(RuntimeService::class.java.name, "io.github.opencodeandroid.desktop.DesktopService") }) { "stop-user-services-first" }
        check(manager.runningAppProcesses.orEmpty().none { it.processName == "$packageName:lifecycle_probe" }) { "another-probe-is-alive" }
        LocalOpenCode("").assertPortAvailable()
    }
    private fun startProbe(action: String, slot: Int = 0) {
        val request = Intent(this, LifecycleProbeService::class.java).setAction(action).putExtra("slot", slot)
        if (action in setOf(RuntimeService.STOP, LifecycleProbeService.SOAK_TERMINAL, LifecycleProbeService.SAMPLE)) startService(request) else startForegroundService(request)
    }
    private fun status(root: File): JSONObject? = runCatching {
        val file = File(root, "status.json"); UpdateFiles.regular(file); check(file.length() <= 16384)
        JSONObject(file.readText()).also { check(it.getString("id") == activeId) }
    }.getOrNull()
    private suspend fun await(milliseconds: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + milliseconds
        while (!predicate()) {
            currentCoroutineContext().ensureActive(); check(SystemClock.elapsedRealtime() < deadline) { "scenario-phase-timed-out" }
            check(SystemClock.elapsedRealtime() - lastPoll < 60000) { "controller-disconnected" }; delay(100)
        }
    }
    private suspend fun cleanup(root: File, id: String) {
        check(root.canonicalFile.parentFile == cacheDir.canonicalFile && root.name == "update-lifecycle-$id")
        runCatching { startProbe(RuntimeService.STOP) }
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (status(root)?.getBoolean("busy") == true && SystemClock.elapsedRealtime() < deadline) delay(100)
        val processName = "$packageName:lifecycle_probe"
        getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().filter { it.processName == processName }.forEach {
            check(it.uid == Process.myUid() && it.pid != Process.myPid())
            check(JSONObject(File(cacheDir, "lifecycle-probe-profile.json").readText()).getString("id") == id)
            Process.killProcess(it.pid)
            val goneDeadline = SystemClock.elapsedRealtime() + 10000
            while (true) {
                val alive = try { android.system.Os.kill(it.pid, 0); true } catch (error: android.system.ErrnoException) { error.errno != android.system.OsConstants.ESRCH }
                if (!alive) break
                check(SystemClock.elapsedRealtime() < goneDeadline); delay(50)
            }
        }
        stopService(Intent(this, LifecycleProbeService::class.java))
        ArchiveTraversal.remove(root.toPath())
        val config = File(cacheDir, "lifecycle-probe-profile.json")
        if (config.isFile && JSONObject(config.readText()).getString("id") == id) check(config.delete())
        File(applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty().filter { it.name.startsWith("lifecycle-$id-") && it.name.endsWith(".xml") }
            .forEach { deleteSharedPreferences(it.name.removeSuffix(".xml")) }
    }
    private fun notification(id: String): Notification {
        val cancel = PendingIntent.getService(this, 7, Intent(this, BackgroundDiagnosticsService::class.java).setAction(CANCEL_ACTION).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("隔离后台验收")
            .setContentText("只运行新建测试工作站，最长 15 分钟，可随时取消。")
            .addAction(Notification.Action.Builder(null, "取消验收", cancel).build()).setOngoing(true).build()
    }
    override fun onDestroy() { worker?.cancel(); scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "background-diagnostics"
        private const val CANCEL_ACTION = "opencode.diagnostics.CANCEL"
        private val C_PROGRAM = """
            #include <stdio.h>
            #include <stdlib.h>
            #include <unistd.h>
            int main(void) {
                const size_t n = 16u * 1024u * 1024u;
                volatile unsigned char *p = malloc(n);
                if (!p) return 2;
                for (unsigned round = 0; round < 200; ++round) {
                    for (size_t i = 0; i < n; i += 4096) p[i] = (unsigned char) round;
                    usleep(100000);
                }
                free((void *) p); puts("memory-ok"); return 0;
            }
        """.trimIndent()
    }
}
