package io.github.opencodeandroid.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.github.opencodeandroid.backup.BackupJson
import io.github.opencodeandroid.runtime.update.UpdateFiles
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Debug-only real RuntimeService, in a separate Android process and a fresh APK-installed Linux tree. */
class LifecycleProbeService : RuntimeService() {
    private val monitor = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val profile by lazy {
        val config = File(super.getCacheDir(), "lifecycle-probe-profile.json")
        UpdateFiles.regular(config)
        val id = BackupJson.read(config).getString("id").also { check(UUID.fromString(it).toString() == it) }
        val root = File(super.getCacheDir().canonicalFile, "update-lifecycle-$id")
        check(root.isDirectory && root.canonicalFile == root.absoluteFile && root.parentFile.canonicalFile == super.getCacheDir().canonicalFile)
        val files = File(root, "files").apply { check(isDirectory || mkdir()) }
        val wrapped = object : ContextWrapper(this@LifecycleProbeService) {
            override fun getFilesDir() = files
            override fun getApplicationContext(): Context = this
            override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
            override fun getNoBackupFilesDir() = File(root, "nobackup").apply { check(isDirectory || mkdir()) }
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("lifecycle-$id-$name", mode)
        }
        Triple(id, root, wrapped)
    }
    override fun runtimeContext(): Context = profile.third
    override fun onCreate() {
        super.onCreate()
        monitor.launch { RuntimeState.current.collect { status() } }
        monitor.launch { TerminalSessions.sessions.collect { status() } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == SAMPLE) { status(); return START_NOT_STICKY }
        if (intent?.action == KILL_GUEST) {
            check(RuntimeState.current.value.pairingUrl != null)
            val active = RuntimeService::class.java.getDeclaredField("process").apply { isAccessible = true }.get(this) as? NativeProcess
            checkNotNull(active).destroyForcibly()
            return START_NOT_STICKY
        }
        if (intent?.action == SOAK_TERMINAL) {
            val slot = intent.getIntExtra("slot", 0); check(slot in 1..8)
            check(RuntimeState.current.value.pairingUrl != null)
            val pending = TerminalSessions.open("/workspace")
            monitor.launch {
                val shell = checkNotNull(pending?.await())
                val script = "for cycle in 1 2 3 4 5 6; do gcc /workspace/soak.c -O0 -o /workspace/load-$slot && /workspace/load-$slot >> /workspace/output-$slot || break; done; printf done > /workspace/done-$slot\n"
                check(shell.write(script.toByteArray())); status()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == OPEN_TERMINAL) {
            val slot = intent.getIntExtra("slot", 0); check(slot in 1..2)
            check(RuntimeState.current.value.pairingUrl != null)
            val pending = TerminalSessions.open("/workspace")
            monitor.launch {
                val shell = checkNotNull(pending?.await())
                val compile = if (slot == 1) "gcc /workspace/main.c -o /workspace/program && /workspace/program > /workspace/result.txt; " else ""
                check(shell.write((compile + "printf 'slot:$slot\\n' >> /workspace/executions; printf '%s' \$\$ > /workspace/tty-$slot.pid; " +
                    "sleep 300 & child=\$!; printf '%s' \"\$child\" > /workspace/sleep-$slot.pid; wait \"\$child\"\n").toByteArray()))
                status()
            }
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }
    @Synchronized private fun status() {
        val value = RuntimeState.current.value
        val memory = android.app.ActivityManager.RunningAppProcessInfo().also { android.app.ActivityManager.getMyMemoryState(it) }
        val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val native = RuntimeService::class.java.getDeclaredField("process").apply { isAccessible = true }.get(this) as? NativeProcess
        UpdateFiles.json(File(profile.second, "status.json"), JSONObject().put("id", profile.first).put("pid", android.os.Process.myPid())
            .put("phase", value.phase).put("busy", value.busy).put("ready", value.ready).put("paired", value.pairingUrl != null)
            .put("workspaceDirectory", WorkspaceContext.directory.value)
            .put("workspaceSession", WorkspaceContext.localSession(value.workspaceUrl) ?: JSONObject.NULL)
            .put("guestPid", native?.pid ?: 0).put("terminals", JSONArray(TerminalSessions.sessions.value.map { it.id }))
            .put("terminalPids", JSONArray(TerminalSessions.sessions.value.map { it.supervisorPid }))
            .put("exits", JSONArray(TerminalSessions.sessions.value.map { it.exit.value ?: JSONObject.NULL }))
            .put("sampleTime", android.os.SystemClock.elapsedRealtime()).put("uid", android.os.Process.myUid())
            .put("importance", memory.importance).put("pssKiB", android.os.Debug.getPss())
            .put("heapUsed", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            .put("fdCount", File("/proc/self/fd").list()?.size ?: JSONObject.NULL)
            .put("interactive", getSystemService(android.os.PowerManager::class.java).isInteractive)
            .put("locked", getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)
            .put("plugged", battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1))
    }
    override fun onDestroy() { runCatching { status() }; monitor.cancel(); super.onDestroy() }
    companion object {
        const val OPEN_TERMINAL = "opencode.lifecycle-probe.OPEN_TERMINAL"
        const val SOAK_TERMINAL = "opencode.lifecycle-probe.SOAK_TERMINAL"
        const val SAMPLE = "opencode.lifecycle-probe.SAMPLE"
        const val KILL_GUEST = "opencode.lifecycle-probe.KILL_GUEST"
    }
}
