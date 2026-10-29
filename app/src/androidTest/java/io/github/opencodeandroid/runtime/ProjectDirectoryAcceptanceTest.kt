package io.github.opencodeandroid.runtime

import io.github.opencodeandroid.ui.nativeLabel

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.desktop.DesktopService
import io.github.opencodeandroid.desktop.DesktopState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProjectDirectoryAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun localSessionLinksCannotSelectRemoteOrForgedNativeDirectories() {
        val link = WorkspaceContext.sessionUrl("ses_fixture_123")
        assertEquals("ses_fixture_123", WorkspaceContext.localSession(link))
        assertEquals("ses_fixture_123", WorkspaceContext.localSession("$link?panel=files#editor"))
        listOf(link.replace(":49374", ":6080"), link.replace("http:", "https:"), link.replace("127.0.0.1", "localhost"),
            link.replace("/server/", "/server/remote-"), link.replace("/session/", "/session/%2F"),
            link.replace("http://", "http://untrusted@"), "$link/extra", LocalOpenCode.ORIGIN + "/auth/connect/not-a-session").forEach { assertNull(it, WorkspaceContext.localSession(it)) }
        assertEquals("", WorkspaceContext.sharedRelative("/workspace"))
        assertEquals("工程 with space", WorkspaceContext.sharedRelative("/workspace/工程 with space"))
        assertNull(WorkspaceContext.sharedRelative("/workspace-elsewhere/code"))
        assertNull(WorkspaceContext.sharedRelative("/workspace/link/../code"))
        assertEquals("/workspace/link/../code", WorkspaceContext.validDirectory("/workspace/link/../code"))
        val original = WorkspaceContext.directory.value
        try {
            val stale = WorkspaceContext.generation
            WorkspaceContext.select("/workspace/new-choice")
            WorkspaceContext.selectIfCurrent("/workspace/stale-response", stale)
            assertEquals("/workspace/new-choice", WorkspaceContext.directory.value)
        } finally { WorkspaceContext.select(original) }
    }

    @Test fun filesOfficialSessionPtyAndDesktopShareRealProjectDirectories() {
        ui.runOnIdle {
            ui.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ui.activity.setTurnScreenOn(true)
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        val paths = RuntimePaths(context).apply { prepare() }
        val original = WorkspaceContext.directory.value
        val fixture = File(paths.workspace, ".directory-check-${UUID.randomUUID()}").apply { check(mkdir()) }
        val a = File(fixture, "工程 A ; ${'$'}(touch sentinel)").apply { check(mkdir()) }
        val b = File(fixture, "工程 B with space").apply { check(mkdir()) }
        File(a, "marker-a.txt").writeText("A")
        File(b, "marker-b.txt").writeText("B")
        val guest = "/workspace/${fixture.name}"
        val directoryA = "$guest/${a.name}"; val directoryB = "$guest/${b.name}"
        val ownedSessions = linkedSetOf<String>()
        try {
            WorkspaceContext.select("/workspace")
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.START))
            ui.waitUntil(120000) { RuntimeState.current.value.pairingUrl != null || RuntimeState.current.value.phase == "Needs attention" }
            assertNotNull(RuntimeState.current.value.log, RuntimeState.current.value.pairingUrl)
            destination(3)
            ui.onNodeWithTag("files-list").performScrollToNode(hasText(fixture.name))
            ui.onNodeWithText(fixture.name).performClick(); ui.onNodeWithText(a.name).performClick()
            ui.onNodeWithTag("files-open-code").performClick()
            ui.waitUntil(20000) { WorkspaceContext.localSession(browserUrl(49374)) != null }
            val firstURL = requireNotNull(browserUrl(49374))
            val first = WorkspaceContext.localSession(firstURL)!!; ownedSessions += first
            assertEquals(directoryA, LocalOpenCode(CredentialVault(paths).password()).sessionDirectory(first))
            awaitComposer(first)
            ui.waitUntil(10000) { WorkspaceContext.directory.value == directoryA }
            val shellA = runBlocking { TerminalSessions.open()?.await() }!!
            shellA.write("pwd > .pty-a\r".toByteArray())
            awaitFile(File(a, ".pty-a"), directoryA)

            val secondURL = LocalOpenCode(CredentialVault(paths).password()).createSession(directoryB)
            val second = WorkspaceContext.localSession(secondURL)!!; ownedSessions += second
            // Exercise HTML5 navigation used by the official router, rather than a page reload.
            ui.runOnIdle { browser(49374)!!.evaluateJavascript("history.pushState({},'',${JSONObject.quote(secondURL)});window.dispatchEvent(new PopStateEvent('popstate'))", null) }
            ui.waitUntil(10000) { WorkspaceContext.directory.value == directoryB }
            awaitComposer(second)
            val shellB = runBlocking { TerminalSessions.open()?.await() }!!
            shellB.write("pwd > .pty-b\r".toByteArray()); awaitFile(File(b, ".pty-b"), directoryB)
            shellA.write("pwd > .pty-a-still\r".toByteArray()); awaitFile(File(a, ".pty-a-still"), directoryA)
            assertFalse("A directory name must never execute shell syntax", File(a, "sentinel").exists())
            destination(3)
            ui.onNodeWithText("marker-b.txt").assertIsDisplayed()
            val count = TerminalSessions.sessions.value.size
            ui.onNodeWithTag("files-open-terminal").performClick()
            ui.waitUntil(10000) { TerminalSessions.sessions.value.size == count + 1 }
            val nativeTerminal = TerminalSessions.sessions.value.last()
            assertEquals(directoryB, nativeTerminal.directory)
            ui.waitUntil(5000) { TerminalSessions.focusedSession.value == nativeTerminal.id }
            nativeTerminal.write("pwd > .pty-native\r".toByteArray()); awaitFile(File(b, ".pty-native"), directoryB)

            destination(3); ui.onNodeWithText("marker-b.txt").performClick()
            ui.onNodeWithTag("files-editor").performTextInput("unsaved project text")
            destination(0)
            ui.runOnIdle { browser(49374)!!.loadUrl(firstURL) }
            ui.waitUntil(10000) { WorkspaceContext.directory.value == directoryA }
            destination(3)
            ui.onNodeWithText(nativeLabel("放弃未保存的更改？")).assertIsDisplayed()
            ui.onNodeWithText(nativeLabel("继续编辑")).performClick()
            ui.onNodeWithTag("files-editor").assertTextContains("unsaved project text", substring = true)
            assertEquals("B", File(b, "marker-b.txt").readText())

            WorkspaceContext.select(directoryB)
            context.startForegroundService(Intent(context, DesktopService::class.java).setAction(DesktopService.START))
            ui.waitUntil(150000) { DesktopState.current.value.connected || DesktopState.current.value.phase == "需要处理" }
            assertTrue(DesktopState.current.value.log, DesktopState.current.value.connected)
            awaitThunar(b.name)
            WorkspaceContext.select(directoryA)
            destination(2)
            ui.waitUntil(15000) { browser(6080) != null }
            clickDesktopProject()
            awaitThunar(a.name)
            assertFalse(File(a, "sentinel").exists())
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "PASS native folder -> official session and router -> project context; real PTY CWD, existing-shell isolation, unsaved editor and real Thunar project windows (Unicode/spaces/metacharacters).") })
        } finally {
            for (id in ownedSessions) runCatching {
                val info = request("GET", "/api/session/$id").getJSONObject("data")
                check(info.getJSONObject("location").getString("directory").startsWith("$guest/"))
                request("DELETE", "/api/session/$id")
            }
            context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP))
            ui.waitUntil(20000) { !DesktopState.current.value.busy }
            context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
            ui.waitUntil(20000) { !RuntimeState.current.value.busy }
            WorkspaceContext.select(original)
            check(fixture.canonicalFile.parentFile == paths.workspace.canonicalFile)
            java.nio.file.Files.walk(fixture.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }

    private fun destination(page: Int) {
        try { ui.waitUntil(7000) { runCatching { ui.onAllNodesWithTag("workspace-orb").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) } }
        catch (failure: Throwable) {
            var details = ""
            instrumentation.runOnMainSync {
                val activity = ui.activity
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val classes = mutableListOf<String>()
                fun visit(view: View, depth: Int) { classes += "${view.javaClass.simpleName}:${view.visibility}"; if (depth < 3 && view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1) }
                visit(content, 0)
                details = "lifecycle=${activity.lifecycle.currentState},finishing=${activity.isFinishing},destroyed=${activity.isDestroyed},focus=${activity.hasWindowFocus()},locked=${activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked},interactive=${activity.getSystemService(android.os.PowerManager::class.java).isInteractive},phase=${RuntimeState.current.value.phase},views=$classes"
            }
            throw AssertionError(details, failure)
        }
        ui.onNodeWithTag("workspace-orb").performClick(); ui.onNodeWithTag("workspace-destination-$page").performClick()
    }
    private fun awaitFile(file: File, expected: String) { ui.waitUntil(10000) { file.isFile && file.readText().trim() == expected }; assertEquals(expected, file.readText().trim()) }
    private fun browserUrl(port: Int): String? {
        var url: String? = null
        instrumentation.runOnMainSync { url = browser(port)?.url }
        return url
    }
    private fun awaitComposer(id: String) {
        ui.waitUntil(20000) {
            val view = browser(49374) ?: return@waitUntil false
            val result = javascript(view, "JSON.stringify({path:location.pathname,composer:!!document.querySelector('[contenteditable=true]'),height:document.getElementById('root')?.clientHeight||0})")
            result?.optString("path")?.endsWith("/$id") == true && result.optBoolean("composer") && result.optInt("height") > 100
        }
    }
    private fun awaitThunar(name: String) {
        ui.waitUntil(20000) {
            val child = ProotCommand(RuntimePaths(context), 1000).process(listOf("/usr/bin/xwininfo", "-root", "-tree"), mapOf("DISPLAY" to ":1")).start()
            try { val output = child.inputStream.bufferedReader().use { it.readText() }; child.waitFor(3, TimeUnit.SECONDS); output.contains(name) }
            finally { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
        }
    }
    private fun clickDesktopProject() {
        var position: JSONObject? = null
        ui.waitUntil(15000) {
            position = browser(6080)?.let { javascript(it, "JSON.stringify((()=>{const button=document.getElementById('project-files');const r=button.getBoundingClientRect();return {ready:window.desktopConnected===true&&typeof button.onclick==='function',x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth}})())") }
            position?.optBoolean("ready") == true
        }
        onView(isAssignableFrom(WebView::class.java)).perform(GeneralClickAction(Tap.SINGLE, { view ->
            val location = IntArray(2); view.getLocationOnScreen(location)
            val scale = view.width / position!!.getDouble("width")
            floatArrayOf((location[0] + position!!.getDouble("x") * scale).toFloat(), (location[1] + position!!.getDouble("y") * scale).toFloat())
        }, Press.FINGER))
    }
    private fun browser(port: Int): WebView? {
        var result: WebView? = null
        val inspect = {
            fun visit(view: View) { if (view is WebView && view.url?.let { Uri.parse(it).port } == port) result = view; if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
            visit(ui.activity.window.decorView)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) inspect() else instrumentation.runOnMainSync(inspect)
        return result
    }
    private fun javascript(view: WebView, expression: String): JSONObject? {
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(expression) { result.set(it) } }
        val until = System.currentTimeMillis() + 2000
        while (result.get() == null && System.currentTimeMillis() < until) Thread.sleep(20)
        return runCatching { JSONObject(JSONTokener(result.get()).nextValue() as String) }.getOrNull()
    }
    private fun request(method: String, path: String): JSONObject {
        val connection = URL(LocalOpenCode.ORIGIN + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 3000; connection.readTimeout = 10000
            connection.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("opencode:${CredentialVault(RuntimePaths(context)).password()}".toByteArray(), android.util.Base64.NO_WRAP))
            check(connection.responseCode in 200..299)
            return connection.inputStream.bufferedReader().use { runCatching { JSONObject(it.readText()) }.getOrDefault(JSONObject()) }
        } finally { connection.disconnect() }
    }
}
