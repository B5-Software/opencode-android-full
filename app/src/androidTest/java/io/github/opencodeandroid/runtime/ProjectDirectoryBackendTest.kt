package io.github.opencodeandroid.runtime

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.desktop.DesktopService
import io.github.opencodeandroid.desktop.DesktopState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Linux/API acceptance can proceed even while Android's secure lock screen is closed. */
@RunWith(AndroidJUnit4::class)
class ProjectDirectoryBackendTest {
    @Test fun officialSessionAndRealPtyAndThunarUseSelectedDirectories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context).apply { prepare() }
        WorkspaceContext.initialize(context)
        val original = WorkspaceContext.directory.value
        val fixture = File(paths.workspace, ".directory-runtime-${UUID.randomUUID()}").apply { check(mkdir()) }
        val a = File(fixture, "项目 A ; ${'$'}(touch sentinel)").apply { check(mkdir()) }
        val b = File(fixture, "项目 B with spaces").apply { check(mkdir()) }
        val directoryA = "/workspace/${fixture.name}/${a.name}"; val directoryB = "/workspace/${fixture.name}/${b.name}"
        val sessions = mutableListOf<String>()
        try {
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.START))
            await(120000) { RuntimeState.current.value.pairingUrl != null || RuntimeState.current.value.phase == "Needs attention" }
            assertNotNull(RuntimeState.current.value.log, RuntimeState.current.value.pairingUrl)
            val local = LocalOpenCode(CredentialVault(paths).password())
            local.registerDirectory("/workspace")
            val idA = WorkspaceContext.localSession(local.createSession(directoryA))!!; sessions += idA
            val idB = WorkspaceContext.localSession(local.createSession(directoryB))!!; sessions += idB
            assertEquals(directoryA, local.sessionDirectory(idA)); assertEquals(directoryB, local.sessionDirectory(idB))
            WorkspaceContext.select(directoryA)
            val shellA = runBlocking { TerminalSessions.open()?.await() }!!
            shellA.write("pwd > .cwd-a\r".toByteArray())
            awaitFile(File(a, ".cwd-a"), directoryA)
            WorkspaceContext.select(directoryB)
            val shellB = runBlocking { TerminalSessions.open()?.await() }!!
            shellB.write("pwd > .cwd-b\r".toByteArray()); awaitFile(File(b, ".cwd-b"), directoryB)
            shellA.write("pwd > .cwd-a-still\r".toByteArray()); awaitFile(File(a, ".cwd-a-still"), directoryA)
            assertEquals(shellB.id, TerminalSessions.focusedSession.value)
            java.nio.file.Files.createSymbolicLink(File(a, "linked-project").toPath(), java.nio.file.Paths.get("../${b.name}"))
            val physicalParent = "/workspace/${fixture.name}"
            val linkShell = runBlocking { TerminalSessions.open("$directoryA/linked-project/..")?.await() }!!
            linkShell.write("pwd > .cwd-physical\r".toByteArray())
            awaitFile(File(fixture, ".cwd-physical"), physicalParent)
            context.startForegroundService(Intent(context, DesktopService::class.java).setAction(DesktopService.START))
            await(150000) { DesktopState.current.value.connected || DesktopState.current.value.phase == "需要处理" }
            assertTrue(DesktopState.current.value.log, DesktopState.current.value.connected)
            await(20000) { windowContains(paths, b.name) }
            val desktopPid = File(paths.temporary, ".X1-lock").readText().trim()
            assertTrue(desktopPid.matches(Regex("[0-9]+")))
            WorkspaceContext.select(directoryA)
            context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.OPEN_PROJECT))
            await(20000) { windowContains(paths, a.name) }
            assertEquals("Opening the current project must retain the X server", desktopPid, File(paths.temporary, ".X1-lock").readText().trim())
            assertFalse(File(a, "sentinel").exists())
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "PASS real official session directory, PTY pwd and existing-shell isolation; real Thunar startup/current-project windows with unchanged X server. Unicode, spaces and shell metacharacters remain literal.") })
        } finally {
            for (id in sessions) runCatching {
                val connection = URL(LocalOpenCode.ORIGIN + "/api/session/$id").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "DELETE"; connection.connectTimeout = 2000; connection.readTimeout = 5000
                    connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("opencode:${CredentialVault(paths).password()}".toByteArray(), android.util.Base64.NO_WRAP))
                    check(connection.responseCode in 200..299)
                    connection.inputStream.close()
                } finally { connection.disconnect() }
            }
            context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP))
            await(20000) { !DesktopState.current.value.busy }
            context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
            await(20000) { !RuntimeState.current.value.busy }
            await(5000) { !File(paths.config, "bridge-auth.json").exists() }
            assertFalse("The D-Bus session address must be removed", File(paths.desktop, "run/dbus-address").exists())
            WorkspaceContext.select(original)
            check(fixture.canonicalFile.parentFile == paths.workspace.canonicalFile)
            java.nio.file.Files.walk(fixture.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }

    private fun awaitFile(file: File, directory: String) { await(10000) { file.isFile && file.readText().trim() == directory }; assertEquals(directory, file.readText().trim()) }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Timed out after $timeout ms", condition())
    }
    private fun windowContains(paths: RuntimePaths, name: String): Boolean {
        val child = ProotCommand(paths, 1000).process(listOf("/usr/bin/xwininfo", "-root", "-tree"), mapOf("DISPLAY" to ":1")).start()
        return try { val text = child.inputStream.bufferedReader().use { it.readText() }; child.waitFor(3, TimeUnit.SECONDS); text.contains(name) }
        finally { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
    }
}
