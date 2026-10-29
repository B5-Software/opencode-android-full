package io.github.opencodeandroid.runtime

import android.app.ActivityManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Repeated real service teardown in an owned, fresh APK Debian tree; no normal project mounts. */
class RuntimeRestartAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private data class Task(val pid: Int, val birth: String, val state: String, val parent: Int)
    private fun task(pid: Int): Task? = runCatching {
        val root = File("/proc/$pid")
        val uid = File(root, "status").readLines().first { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+")).first().toInt()
        check(uid == android.os.Process.myUid())
        val fields = File(root, "stat").readText().substringAfterLast(") ").split(' ').filter(String::isNotEmpty)
        Task(pid, fields[19], fields[0], fields[1].toInt())
    }.getOrNull()
    private fun gone(old: Task): Boolean {
        task(old.pid)?.let { return it.birth != old.birth || it.state in setOf("Z", "X") }
        return try { Os.kill(old.pid, 0); false }
        catch (failure: android.system.ErrnoException) { failure.errno == android.system.OsConstants.ESRCH }
    }
    private fun descendants(roots: Set<Int>): List<Task> {
        val owned = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull()?.let(::task) }
        val byId = owned.associateBy { it.pid }; val byParent = owned.groupBy { it.parent }
        val seen = roots.toMutableSet(); val queue = java.util.ArrayDeque<Int>().apply { addAll(roots) }; val result = mutableListOf<Task>()
        while (queue.isNotEmpty()) {
            val value = byId[queue.removeFirst()] ?: continue; result += value; check(result.size <= 256)
            byParent[value.pid].orEmpty().forEach { if (seen.add(it.pid)) queue.add(it.pid) }
        }
        return result
    }
    private fun await(timeout: Long = 60000, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < until) { "Runtime restart acceptance timed out" }; Thread.sleep(25) }
    }
    private fun listening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", LocalOpenCode.PORT), 250) }; true
    } catch (_: java.io.IOException) { false }

    /** Public APIs, this process's descriptors only, fixed service port; no payload or unrelated endpoint. */
    private fun ownConnections(): JSONObject {
        var local = 0; var remote = 0
        File("/proc/self/fd").list().orEmpty().mapNotNull(String::toIntOrNull).forEach { descriptor ->
            runCatching { ParcelFileDescriptor.fromFd(descriptor).use { duplicate ->
                val address = Os.getsockname(duplicate.fileDescriptor) as? InetSocketAddress
                if (address?.port == LocalOpenCode.PORT && address.address?.isLoopbackAddress == true) local++
                val peer = runCatching { Os.getpeername(duplicate.fileDescriptor) as? InetSocketAddress }.getOrNull()
                if (peer?.port == LocalOpenCode.PORT && peer.address?.isLoopbackAddress == true) remote++
            } }
        }
        return JSONObject().put("ownLocalServicePort", local).put("ownRemoteServicePort", remote)
    }
    private fun report(value: JSONObject) = instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "RESTART_SAMPLE $value\n") })

    @Test fun repeatedStopNativeKillAndHostDeathReleasePortAndPreserveOfficialSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeRuntimeRestart") == "true")
        val manager = context.getSystemService(ActivityManager::class.java)
        val processName = context.packageName + ":lifecycle_probe"
        check(manager.runningAppProcesses.orEmpty().none { it.processName == processName || it.processName == context.packageName + ":background_diagnostics" })
        LocalOpenCode("fixture-unused").assertPortAvailable()
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "update-lifecycle-$id").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val paths = RuntimePaths(object : ContextWrapper(context) { override fun getFilesDir() = files })
        val config = File(context.cacheDir, "lifecycle-probe-profile.json")
        if (UpdateFiles.exists(config)) { UpdateFiles.regular(config); check(config.delete()) }
        UpdateFiles.json(config, JSONObject().put("id", id))
        val component = ComponentName(context, LifecycleProbeService::class.java)
        fun status(): JSONObject? = runCatching {
            val source = File(root, "status.json"); UpdateFiles.regular(source); check(source.length() <= 16384)
            JSONObject(source.readText()).also { check(it.getString("id") == id) }
        }.getOrNull()
        fun start(action: String, slot: Int = 0) {
            val intent = Intent().setComponent(component).setAction(action).putExtra("slot", slot)
            if (action in setOf(RuntimeService.STOP, LifecycleProbeService.OPEN_TERMINAL, LifecycleProbeService.KILL_GUEST)) context.startService(intent)
            else context.startForegroundService(intent)
        }
        fun killHost(value: JSONObject) {
            val pid = value.getInt("pid"); check(pid != android.os.Process.myPid())
            check(manager.runningAppProcesses.orEmpty().any { it.pid == pid && it.uid == android.os.Process.myUid() && it.processName == processName })
            android.os.Process.killProcess(pid)
        }
        val samples = JSONArray()
        try {
            start(RuntimeService.INSTALL)
            await(180000) { status()?.let { it.getBoolean("ready") && !it.getBoolean("busy") } == true }
            assertTrue(paths.installed)
            val source = "#include <stdio.h>\nint main(void) { puts(\"restart-persistent-work\"); return 0; }\n"
            File(paths.workspace, "main.c").writeText(source)
            val client = LocalOpenCode(CredentialVault(paths).password())
            var session: String? = null
            repeat(15) { round ->
                File(root, "status.json").delete()
                start(RuntimeService.START)
                await { status()?.let { it.getBoolean("paired") && it.getBoolean("busy") } == true }
                if (session == null) session = client.createSession("/workspace").substringAfterLast('/')
                assertEquals("/workspace", client.sessionDirectory(checkNotNull(session)))
                assertEquals(0, checkNotNull(status()).getJSONArray("terminals").length())
                val before = File(paths.workspace, "executions").takeIf(File::isFile)?.readLines()?.size ?: 0
                assertEquals("Restart replayed a prior terminal command", round, before)
                for (name in listOf("tty-1.pid", "sleep-1.pid", "result.txt", "program")) File(paths.workspace, name).let { if (it.exists()) check(it.delete()) }
                start(LifecycleProbeService.OPEN_TERMINAL, 1)
                await { File(paths.workspace, "executions").takeIf(File::isFile)?.readLines()?.size == before + 1 &&
                    File(paths.workspace, "result.txt").takeIf(File::isFile)?.readText()?.trim() == "restart-persistent-work" &&
                    File(paths.workspace, "sleep-1.pid").takeIf(File::isFile)?.length()?.let { it > 0 } == true &&
                    status()?.getJSONArray("terminalPids")?.length()?.let { it > 0 } == true }
                val live = checkNotNull(status())
                val terminalIds = live.getJSONArray("terminalPids").let { array -> (0 until array.length()).map(array::getInt) }
                val captured = descendants((listOf(live.getInt("guestPid")) + terminalIds + if (round % 3 == 2) listOf(live.getInt("pid")) else emptyList()).toSet())
                val shell = File(paths.workspace, "tty-1.pid").readText().toInt()
                val sleeper = File(paths.workspace, "sleep-1.pid").readText().toInt()
                check(captured.size >= 4 && terminalIds.isNotEmpty())
                check(captured.any { it.pid == shell } && captured.any { it.pid == sleeper })
                // Several real authenticated requests create ordinary client/server socket teardown.
                repeat(8) { assertEquals("/workspace", client.sessionDirectory(checkNotNull(session))) }
                val mode = listOf("stop", "native-kill", "host-kill")[round % 3]
                val began = SystemClock.elapsedRealtime()
                when (mode) { "stop" -> start(RuntimeService.STOP); "native-kill" -> start(LifecycleProbeService.KILL_GUEST); else -> killHost(live) }
                var exitedAt: Long? = null
                var busy = 0; var listeners = 0; var listenersAfterExit = 0
                var inventory: JSONObject? = null
                await(15000) {
                    val exited = captured.all(::gone)
                    if (exited && exitedAt == null) exitedAt = SystemClock.elapsedRealtime() - began
                    val available = try { client.assertPortAvailable(); true }
                    catch (_: java.net.BindException) {
                        busy++; val alive = listening()
                        if (alive) { listeners++; if (exited) listenersAfterExit++ }
                        if (inventory == null) inventory = ownConnections()
                        false
                    }
                    exited && available
                }
                val sample = JSONObject().put("round", round).put("mode", mode).put("captured", captured.size)
                    .put("allGoneMs", exitedAt).put("portReleasedMs", SystemClock.elapsedRealtime() - began)
                    .put("bindBusySamples", busy).put("listenerSamples", listeners).put("listenersAfterExit", listenersAfterExit)
                    .put("ownConnectionsAtBusy", inventory ?: JSONObject.NULL)
                samples.put(sample); report(sample)
                if (mode != "host-kill") await { status()?.getBoolean("busy") == false }
                assertEquals(source, File(paths.workspace, "main.c").readText())
                assertEquals(before + 1, File(paths.workspace, "executions").readLines().size)
            }
            // Final explicit recovery proves that the final exit did not merely abandon a dead backend.
            File(root, "status.json").delete()
            start(RuntimeService.START); await { status()?.getBoolean("paired") == true }
            assertEquals("/workspace", client.sessionDirectory(checkNotNull(session)))
            assertEquals(0, checkNotNull(status()).getJSONArray("terminals").length())
            assertEquals(15, File(paths.workspace, "executions").readLines().size)
            start(RuntimeService.STOP); await { status()?.getBoolean("busy") == false }
            assertFalse(DevelopmentSessionJournal(paths).snapshot().needsRestart(DevelopmentKind.RUNTIME))
            report(JSONObject().put("passed", true).put("rounds", samples))
        } finally {
            runCatching { start(RuntimeService.STOP) }; runCatching { await(10000) { status()?.getBoolean("busy") != true } }
            context.stopService(Intent().setComponent(component))
            val remaining = manager.runningAppProcesses.orEmpty().filter { it.processName == processName && it.uid == android.os.Process.myUid() }
            if (remaining.isNotEmpty()) {
                check(JSONObject(config.readText()).getString("id") == id)
                val old = remaining.map { checkNotNull(task(it.pid)) }
                remaining.forEach { check(it.pid != android.os.Process.myPid()); android.os.Process.killProcess(it.pid) }
                await(10000) { old.all(::gone) }
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
