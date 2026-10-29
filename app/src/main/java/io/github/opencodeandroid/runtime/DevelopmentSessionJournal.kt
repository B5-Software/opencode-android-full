package io.github.opencodeandroid.runtime

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import io.github.opencodeandroid.backup.BackupJson
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class DevelopmentKind { RUNTIME, DESKTOP }
internal enum class DevelopmentOperation(val title: String) {
    OPENCODE("OpenCode 开发会话"), INSTALL("开发环境安装"), UPDATE("Debian 更新"), REPAIR("开发环境修复"),
    VERIFY("开发环境检查"), LANGUAGE_SERVERS("语言服务安装"), RECOVER("环境恢复"),
    DESKTOP("Debian 桌面"), DESKTOP_INSTALL("桌面安装")
}
internal data class DevelopmentRun(val id: String, val owner: String, val pid: Int, val processName: String,
    val operation: DevelopmentOperation, val started: Long, val terminals: Set<Int> = emptySet(),
    val failed: Boolean = false, val exitReason: Int? = null)
internal data class DevelopmentSlot(val active: DevelopmentRun? = null, val interrupted: DevelopmentRun? = null, val acknowledged: Boolean = true)
internal data class DevelopmentHistory(val runtime: DevelopmentSlot = DevelopmentSlot(), val desktop: DevelopmentSlot = DevelopmentSlot()) {
    fun slot(kind: DevelopmentKind) = if (kind == DevelopmentKind.RUNTIME) runtime else desktop
    fun replace(kind: DevelopmentKind, slot: DevelopmentSlot) = if (kind == DevelopmentKind.RUNTIME) copy(runtime = slot) else copy(desktop = slot)
    fun needsRestart(kind: DevelopmentKind) = slot(kind).interrupted != null && !slot(kind).acknowledged
}
internal class DevelopmentRecoveryRequired : IllegalStateException("上次运行已中断，请在运行环境中明确重新启动。")

/** Local metadata only: never stores commands, terminal output, environment variables, keys or URLs. */
internal class DevelopmentSessionJournal(private val paths: RuntimePaths,
    private val owner: String = HOST, private val processId: Int = android.os.Process.myPid(),
    private val processName: String = Application.getProcessName(),
    private val exitReason: (DevelopmentRun) -> Int? = { run -> systemExitReason(paths, run) },
    private val beforeWrite: () -> Unit = {}) {
    internal val file get() = File(paths.files, "maintenance/development-sessions.json")
    private fun parent() { UpdateFiles.directory(File(paths.files, "maintenance")) }
    private fun read(): DevelopmentHistory {
        parent()
        if (!UpdateFiles.exists(file) && !UpdateFiles.exists(File(file.path + ".bak"))) {
            fun legacy(source: File, operation: DevelopmentOperation): DevelopmentSlot {
                val backup = File(source.path + ".bak")
                if (!UpdateFiles.exists(source) && !UpdateFiles.exists(backup)) return DevelopmentSlot()
                for (entry in listOf(source, backup)) if (UpdateFiles.exists(entry)) UpdateFiles.regular(entry)
                val value = android.util.AtomicFile(source).openRead().use { input ->
                    val data = ByteArray(33); var total = 0
                    while (total < data.size) { val count = input.read(data, total, data.size - total); if (count < 0) break; total += count }
                    data.copyOf(total)
                }
                val marker = value.toString(Charsets.US_ASCII).trim()
                check(value.size <= 32 && marker in setOf("running", "stopped"))
                return if (marker == "running") DevelopmentSlot(interrupted = DevelopmentRun(UUID.randomUUID().toString(), ZERO, 0, processName, operation, 0), acknowledged = false) else DevelopmentSlot()
            }
            return DevelopmentHistory(legacy(File(paths.config, "session-state"), DevelopmentOperation.OPENCODE),
                legacy(File(paths.desktop, "session-state"), DevelopmentOperation.DESKTOP))
        }
        for (entry in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) if (UpdateFiles.exists(entry)) UpdateFiles.regular(entry)
        val json = BackupJson.read(file)
        check(number(json, "format", 1, 1) == 1L && json.length() == 3)
        return DevelopmentHistory(slot(json.getJSONObject("runtime")), slot(json.getJSONObject("desktop")))
    }
    private fun slot(json: JSONObject): DevelopmentSlot {
        check(json.length() == 3 && json.get("acknowledged") is Boolean)
        fun record(name: String): DevelopmentRun? {
            if (json.isNull(name)) return null
            val item = json.getJSONObject(name); check(item.length() == 9)
            fun uuid(key: String) = (item.get(key) as String).also { check(UUID.fromString(it).toString() == it) }
            val ids = item.getJSONArray("terminals"); check(ids.length() <= 8)
            val terminals = (0 until ids.length()).map { index ->
                val id = ids.get(index); check(id is Int && id > 0); id
            }.toSet(); check(terminals.size == ids.length())
            val reason = if (item.isNull("exitReason")) null else number(item, "exitReason", 0, 255).toInt()
            check(item.get("failed") is Boolean)
            return DevelopmentRun(uuid("id"), uuid("owner"), number(item, "pid", 0, Int.MAX_VALUE.toLong()).toInt(),
                (item.get("processName") as String).also { check(it.length in 1..256 && it.matches(Regex("[A-Za-z0-9_.:-]+"))) },
                DevelopmentOperation.valueOf(item.getString("operation")), number(item, "started", 0, Long.MAX_VALUE), terminals, item.getBoolean("failed"), reason)
        }
        return DevelopmentSlot(record("active"), record("interrupted"), json.getBoolean("acknowledged"))
    }
    private fun write(value: DevelopmentHistory) {
        fun record(run: DevelopmentRun?): Any = run?.let {
            JSONObject().put("id", it.id).put("owner", it.owner).put("pid", it.pid).put("processName", it.processName)
                .put("operation", it.operation.name).put("started", it.started).put("terminals", JSONArray(it.terminals.sorted()))
                .put("failed", it.failed).put("exitReason", it.exitReason ?: JSONObject.NULL)
        } ?: JSONObject.NULL
        fun slot(value: DevelopmentSlot) = JSONObject().put("active", record(value.active)).put("interrupted", record(value.interrupted)).put("acknowledged", value.acknowledged)
        val json = JSONObject().put("format", 1).put("runtime", slot(value.runtime)).put("desktop", slot(value.desktop))
        check(json.toString().toByteArray().size <= 16384)
        parent()
        for (entry in listOf(file, File(file.path + ".bak"), File(file.path + ".new"))) if (UpdateFiles.exists(entry)) UpdateFiles.regular(entry)
        beforeWrite(); UpdateFiles.json(file, json)
    }
    private fun reconcile(): DevelopmentHistory {
        var value = read(); val before = value
        for (kind in DevelopmentKind.entries) {
            var slot = value.slot(kind)
            slot.active?.takeIf { it.owner != owner || it.id !in LIVE }?.let { old ->
                LIVE.remove(old.id)
                slot = DevelopmentSlot(interrupted = old.copy(exitReason = exitReason(old)), acknowledged = false)
            }
            slot.interrupted?.takeIf { it.exitReason == null && !it.failed }?.let { old ->
                exitReason(old)?.let { reason -> slot = slot.copy(interrupted = old.copy(exitReason = reason)) }
            }
            value = value.replace(kind, slot)
        }
        // Freeze legacy markers once, even if there were no running sessions.
        if (value != before || !UpdateFiles.exists(file)) write(value)
        return value
    }
    fun snapshot(): DevelopmentHistory = synchronized(LOCK) { reconcile() }
    fun begin(kind: DevelopmentKind, operation: DevelopmentOperation, automatic: Boolean = false): String = synchronized(LOCK) {
        val value = reconcile(); val previous = value.slot(kind)
        if (automatic && value.needsRestart(kind)) throw DevelopmentRecoveryRequired()
        check(previous.active == null) { "此类开发任务仍在运行" }
        val run = DevelopmentRun(UUID.randomUUID().toString(), owner, processId, processName, operation, System.currentTimeMillis().coerceAtLeast(0))
        write(value.replace(kind, previous.copy(active = run, acknowledged = true))); LIVE.add(run.id); run.id
    }
    fun finish(kind: DevelopmentKind, token: String, failed: Boolean) = synchronized(LOCK) {
        val value = read(); val slot = value.slot(kind); val active = slot.active
        if (active?.id != token || active.owner != owner) return@synchronized
        try { write(value.replace(kind, if (failed) DevelopmentSlot(interrupted = active.copy(failed = true), acknowledged = false) else slot.copy(active = null))) }
        finally { LIVE.remove(token) }
    }
    fun terminalOpened(id: Int): String? = synchronized(LOCK) {
        val value = read(); val slot = value.runtime; val active = slot.active ?: return@synchronized null
        if (active.owner != owner) return@synchronized null
        check(id > 0 && active.terminals.size < 8 && id !in active.terminals)
        write(value.copy(runtime = slot.copy(active = active.copy(terminals = active.terminals + id)))); active.id
    }
    fun terminalClosed(token: String, id: Int) = synchronized(LOCK) {
        val value = read(); val slot = value.runtime; val active = slot.active
        if (active?.id != token || active.owner != owner || id !in active.terminals) return@synchronized
        write(value.copy(runtime = slot.copy(active = active.copy(terminals = active.terminals - id))))
    }
    /** Caller must own maintenance; corrupt bytes are retained without following links or touching Linux data. */
    fun reset(lease: RuntimeCoordination.MaintenanceLease) = synchronized(LOCK) {
        lease.checkCurrent(); parent()
        val suffix = UUID.randomUUID().toString()
        val sources = listOf(file, File(file.path + ".bak"), File(file.path + ".new")).filter(UpdateFiles::exists)
        sources.forEach(UpdateFiles::regular)
        for (source in sources) UpdateFiles.move(source, File(source.parentFile, "development-sessions-corrupt-$suffix-${source.name}"))
        write(DevelopmentHistory())
    }
    companion object {
        private val LOCK = Any()
        private val LIVE = mutableSetOf<String>()
        private val HOST = UUID.randomUUID().toString()
        private val ZERO = UUID(0, 0).toString()
        private fun number(json: JSONObject, name: String, minimum: Long, maximum: Long): Long {
            val value = json.get(name); check(value is Int || value is Long)
            return (value as Number).toLong().also { check(it in minimum..maximum) }
        }
        private fun systemExitReason(paths: RuntimePaths, run: DevelopmentRun): Int? {
            if (Build.VERSION.SDK_INT < 30 || run.pid <= 0) return null
            return runCatching {
                paths.applicationContext.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(paths.applicationContext.packageName, run.pid, 4)
                    .firstOrNull { it.processName == run.processName && it.timestamp >= run.started }?.reason?.takeIf { it in 0..255 }
            }.getOrNull()
        }
    }
}
