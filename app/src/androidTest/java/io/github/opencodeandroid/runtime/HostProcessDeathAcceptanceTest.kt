package io.github.opencodeandroid.runtime

import android.app.ActivityManager
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.UUID

/** Kills only the fresh, verified debug sibling process; normal user Linux data is never mounted. */
class HostProcessDeathAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(timeout: Long = 60000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Lifecycle acceptance timed out" }; Thread.sleep(50) }
    }
    private data class Task(val pid: Int, val birth: String, val state: String, val parent: Int)
    private fun task(pid: Int): Task? = runCatching {
        val directory = File("/proc/$pid")
        // /proc directory ownership reflects dumpability and may be root, not the task's real UID.
        val uid = File(directory, "status").readLines().first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toInt()
        check(uid == android.os.Process.myUid())
        val fields = File(directory, "stat").readText().substringAfterLast(") ").split(' ').filter(String::isNotEmpty)
        Task(pid, fields[19], fields[0], fields[1].toInt())
    }.getOrNull()
    private fun noLongerRunnable(old: Task): Boolean {
        task(old.pid)?.let { return it.birth != old.birth || it.state in setOf("Z", "X") }
        return try { android.system.Os.kill(old.pid, 0); false }
        catch (error: android.system.ErrnoException) { error.errno == android.system.OsConstants.ESRCH }
    }
    private fun descendants(roots: Set<Int>): List<Task> {
        // Android restricts task/*/children. Reconstruct the graph from this app UID's actual PPids.
        // No command line, environment, or another UID's task data is retained.
        val owned = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull()?.let(::task) }
        val byId = owned.associateBy { it.pid }; val byParent = owned.groupBy { it.parent }
        val seen = roots.toMutableSet(); val queue = java.util.ArrayDeque<Int>().apply { addAll(roots) }; val result = mutableListOf<Task>()
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst(); val state = byId[pid] ?: continue; result += state; check(result.size <= 256)
            byParent[pid].orEmpty().forEach { child -> if (seen.add(child.pid)) queue.add(child.pid) }
        }
        return result
    }
    @Test fun actualRuntimeHostDeathReapsChildrenPreservesSessionAndRequiresExplicitRestart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeHostProcessDeath") == "true")
        val component = android.content.ComponentName(context, LifecycleProbeService::class.java)
        val manager = context.getSystemService(ActivityManager::class.java)
        val processName = context.packageName + ":lifecycle_probe"
        check(manager.runningAppProcesses.orEmpty().none { it.processName == processName }) { "Another lifecycle probe is still alive" }
        ServerSocket().use { it.reuseAddress = true; it.bind(java.net.InetSocketAddress("127.0.0.1", LocalOpenCode.PORT)) }
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "update-lifecycle-$id").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val paths = RuntimePaths(object : ContextWrapper(context) { override fun getFilesDir() = files })
        val config = File(context.cacheDir, "lifecycle-probe-profile.json")
        if (UpdateFiles.exists(config)) { UpdateFiles.regular(config); check(config.delete()) }
        UpdateFiles.json(config, JSONObject().put("id", id))
        fun status(): JSONObject? = runCatching {
            val file = File(root, "status.json"); UpdateFiles.regular(file); check(file.length() <= 16384)
            JSONObject(file.readText()).also { check(it.getString("id") == id) }
        }.getOrNull()
        fun start(action: String, automatic: Boolean = false, slot: Int = 0) {
            val intent = Intent().setComponent(component).setAction(action).putExtra(RuntimeService.AUTOMATIC_START, automatic).putExtra("slot", slot)
            if (action in setOf(RuntimeService.STOP, LifecycleProbeService.OPEN_TERMINAL)) context.startService(intent) else context.startForegroundService(intent)
        }
        fun killVerifiedProbe() {
            val value = checkNotNull(status()); val pid = value.getInt("pid")
            check(pid != android.os.Process.myPid())
            check(manager.runningAppProcesses.orEmpty().any { it.pid == pid && it.uid == android.os.Process.myUid() && it.processName == processName })
            android.os.Process.killProcess(pid)
        }
        try {
            start(RuntimeService.INSTALL)
            await(180000) { status()?.let { it.getBoolean("ready") && !it.getBoolean("busy") } == true }
            assertTrue(paths.installed)
            val command = ProotCommand(paths)
            val environmentProbe = NativeProcess.Builder(command.arguments(listOf("/bin/sh", "-c",
                "test \"${'$'}DO_NOT_TRACK\" = 1 && test -z \"${'$'}{OTEL_EXPORTER_OTLP_ENDPOINT+x}${'$'}{OTEL_EXPORTER_OTLP_HEADERS+x}${'$'}{SENTRY_DSN+x}${'$'}{PROVIDER_API_KEY+x}\" && printf default-environment-private")),
                command.hostEnvironment() + mapOf("OTEL_EXPORTER_OTLP_ENDPOINT" to "http://127.0.0.1:1/fixture", "OTEL_EXPORTER_OTLP_HEADERS" to "fixture-header",
                    "SENTRY_DSN" to "fixture-dsn", "PROVIDER_API_KEY" to "fixture-provider-value")).start()
            try {
                val output = StringBuilder()
                DiagnosticReaderForPrivacy.read(environmentProbe, output)
                assertTrue(environmentProbe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, environmentProbe.exitValue()); assertEquals("default-environment-private", output.toString())
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "DEFAULT_ENVIRONMENT inheritedOtlp=false inheritedSentry=false inheritedProvider=false doNotTrack=true\n") })
            } finally { environmentProbe.destroyForcibly(); environmentProbe.inputStream.close(); environmentProbe.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
            File(paths.workspace, "main.c").writeText("#include <stdio.h>\nint main(void) { puts(\"persistent-work\"); return 0; }\n")
            start(RuntimeService.START)
            await { status()?.getBoolean("paired") == true }
            val client = LocalOpenCode(CredentialVault(paths).password())
            assertEquals(WorkspaceContext.STARTER_DIRECTORY, checkNotNull(status()).getString("workspaceDirectory"))
            assertTrue(File(paths.workspace, "helloworld").isDirectory)
            assertEquals(WorkspaceContext.STARTER_DIRECTORY, client.sessionDirectory(checkNotNull(status()).getString("workspaceSession")))
            val session = client.createSession("/workspace").substringAfterLast('/')
            // Persist a different confirmed local project, then force a real new Android process.
            context.getSharedPreferences("lifecycle-$id-workspace-context", android.content.Context.MODE_PRIVATE).edit()
                .putString("directory", "/workspace").putString("session-id", session).commit().also { check(it) }
            start(LifecycleProbeService.OPEN_TERMINAL, slot = 1)
            start(LifecycleProbeService.OPEN_TERMINAL, slot = 2)
            await { File(paths.workspace, "executions").takeIf(File::isFile)?.readLines()?.size == 2 &&
                (1..2).all { slot -> File(paths.workspace, "sleep-$slot.pid").let { it.isFile && it.length() > 0 } } }
            assertEquals("persistent-work", File(paths.workspace, "result.txt").readText().trim())
            val live = checkNotNull(status()); val host = live.getInt("pid")
            check(manager.runningAppProcesses.orEmpty().any { it.pid == host && it.uid == android.os.Process.myUid() && it.processName == processName })
            val shells = (1..2).map { File(paths.workspace, "tty-$it.pid").readText().toInt() }
            val sleepers = (1..2).map { File(paths.workspace, "sleep-$it.pid").readText().toInt() }
            val terminalPids = live.getJSONArray("terminalPids").let { array -> (0 until array.length()).map(array::getInt) }
            val captured = descendants((listOf(host, live.getInt("guestPid")) + terminalPids + shells + sleepers).toSet())
            assertTrue("Native descendants must be visible (captured=${captured.size}, uid=${android.os.Process.myUid()})", captured.size >= 5)
            assertTrue((shells + sleepers).all { pid -> captured.any { it.pid == pid } })
            val source = File(paths.workspace, "main.c").readBytes(); val executions = File(paths.workspace, "executions").readBytes()
            val releaseStarted = SystemClock.elapsedRealtime()
            var releaseRetries = 0
            var listenerSamples = 0
            var listenersAfterExit = 0
            var processesExitedMs: Long? = null
            killVerifiedProbe()
            // K30 repeat sampling observed a short listening interval after captured PIDs became
            // non-runnable. Require BOTH process exit and actual reservation within one deadline.
            await(15000) {
                val exited = captured.all(::noLongerRunnable)
                if (exited && processesExitedMs == null) processesExitedMs = SystemClock.elapsedRealtime() - releaseStarted
                val available = try { client.assertPortAvailable(); true }
                catch (_: java.net.BindException) {
                    releaseRetries++
                    val listening = try {
                        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", LocalOpenCode.PORT), 250) }; true
                    } catch (_: java.io.IOException) { false }
                    if (listening) { listenerSamples++; if (exited) listenersAfterExit++ }
                    false
                }
                exited && available
            }
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "PORT_RELEASE retries=$releaseRetries elapsedMs=${SystemClock.elapsedRealtime() - releaseStarted} processesExitedMs=$processesExitedMs listenerSamples=$listenerSamples listenersAfterExit=$listenersAfterExit\n")
            })
            val history = DevelopmentSessionJournal(paths).snapshot()
            assertTrue(history.needsRestart(DevelopmentKind.RUNTIME)); assertEquals(2, history.runtime.interrupted!!.terminals.size)
            assertArrayEquals(source, File(paths.workspace, "main.c").readBytes())
            assertArrayEquals(executions, File(paths.workspace, "executions").readBytes())
            assertEquals("persistent-work", File(paths.workspace, "result.txt").readText().trim())
            // The same real RuntimeService must refuse the automatic notification/deep-link path.
            File(root, "status.json").delete(); start(RuntimeService.START, automatic = true)
            await { status()?.let { it.getString("phase") == "Interrupted" && !it.getBoolean("busy") } == true }
            assertFalse(checkNotNull(status()).getBoolean("paired")); assertEquals(0, checkNotNull(status()).getInt("guestPid"))
            assertTrue(DevelopmentSessionJournal(paths).snapshot().needsRestart(DevelopmentKind.RUNTIME))
            // An explicit restart preserves the real official session and never replays shell input.
            start(RuntimeService.START)
            await { status()?.getBoolean("paired") == true }
            assertEquals("/workspace", client.sessionDirectory(session))
            assertEquals("/workspace", checkNotNull(status()).getString("workspaceDirectory"))
            assertEquals(session, checkNotNull(status()).getString("workspaceSession"))
            assertArrayEquals(source, File(paths.workspace, "main.c").readBytes())
            assertArrayEquals(executions, File(paths.workspace, "executions").readBytes())
            assertEquals(0, checkNotNull(status()).getJSONArray("terminals").length())
            start(RuntimeService.STOP)
            await { status()?.let { !it.getBoolean("busy") && it.getString("phase") == "Stopped" } == true }
            assertFalse(DevelopmentSessionJournal(paths).snapshot().needsRestart(DevelopmentKind.RUNTIME))
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nPASS real RuntimeService in a fresh APK Debian tree: host SIGKILL; ${captured.size} captured processes (host + descendants) no longer runnable; authenticated official session survives; two interrupted PTYs; automatic restart refused; explicit restart does not replay commands; project preserved. Not an LMK/battery/other-API claim.\n") })
        } finally {
            runCatching { start(RuntimeService.STOP) }
            runCatching { await(10000) { status()?.getBoolean("busy") != true } }
            context.stopService(Intent().setComponent(component))
            val remaining = manager.runningAppProcesses.orEmpty().filter { it.processName == processName && it.uid == android.os.Process.myUid() }
            if (remaining.isNotEmpty()) {
                check(JSONObject(config.readText()).getString("id") == id)
                val liveTasks = remaining.map { checkNotNull(task(it.pid)) }
                remaining.forEach { check(it.pid != android.os.Process.myPid()); android.os.Process.killProcess(it.pid) }
                await(10000) { liveTasks.all(::noLongerRunnable) }
            }
            check(root.parentFile == context.cacheDir.canonicalFile)
            UpdateTransactionTest.removeOwnedTestTree(context, root)
            if (config.isFile) config.delete()
            File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
                .filter { it.name.startsWith("lifecycle-$id-") && it.name.endsWith(".xml") }
                .forEach { context.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
        }
    }
}

private object DiagnosticReaderForPrivacy {
    fun read(process: NativeProcess, output: StringBuilder) {
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "privacy-probe-read").apply { isDaemon = true } }
        var complete = false
        try {
            reader.submit { io.github.opencodeandroid.logging.DiagnosticReader.read(process.inputStream, { line ->
                check(output.length + line.length <= 256); output.append(line)
            }) }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            complete = true
        } finally { if (!complete) process.destroyForcibly(); process.inputStream.close(); reader.shutdownNow() }
    }
}
