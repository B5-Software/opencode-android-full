package io.github.opencodeandroid.bridge

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.NativeProcess
import io.github.opencodeandroid.runtime.ProotCommand
import io.github.opencodeandroid.runtime.RuntimePaths
import org.hamcrest.Matchers.allOf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AndroidBridgeTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val paths get() = RuntimePaths(context)
    private val running = mutableListOf<Command>()
    private var intents = false
    @Before fun connect() { AndroidBridge.acquire(context, "bridge-tests") }
    @After fun cleanup() {
        running.forEach { it.process.destroyForcibly(); it.process.waitFor(3, TimeUnit.SECONDS) }
        AndroidBridge.release("bridge-tests")
        if (intents) Intents.release()
    }
    private class Command(val process: NativeProcess) {
        val output = StringBuilder()
        val reader = Thread { try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } } catch (_: java.io.IOException) { } }.apply { start() }
        fun finish(expected: Int = 0): String {
            assertTrue("Linux helper did not finish", process.waitFor(10, TimeUnit.SECONDS)); reader.join(2000)
            assertEquals(output.toString(), expected, process.exitValue())
            return output.toString()
        }
    }
    private fun command(vararg args: String) = Command(ProotCommand(paths).process(args.toList()).start()).also(running::add)
    private fun button(text: String) {
        ui.waitUntil(10000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(text).performClick()
    }
    @Test fun realLinuxXdgOpenRequiresUiAndLaunchesBrowsableHttpIntent() {
        Intents.init(); intents = true
        val url = "https://example.test/device?state=fixture"
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        val client = command("/bin/bash", "-c", "xdg-open '$url'")
        ui.waitUntil(10000) { AndroidBridge.pending.value.isNotEmpty() }
        Thread.sleep(1250) // The socket must remain pending beyond a receive-timeout tick.
        assertTrue("Helper exited while awaiting approval: ${if (client.process.isAlive) "running" else client.process.exitValue()}", client.process.isAlive)
        button("在浏览器中打开")
        client.finish()
        Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(Uri.parse(url)), hasCategories(setOf(Intent.CATEGORY_BROWSABLE))))
        ui.waitUntil(3000) { AndroidBridge.pending.value.isEmpty() }
    }
    @Test fun previewRequiresExplicitNativeClickAndDispatchesTheIsolatedActivity() {
        Intents.init(); intents = true
        Intents.intending(hasComponent(io.github.opencodeandroid.preview.PreviewActivity::class.java.name))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        fun validListener(): java.net.ServerSocket {
            while (true) {
                val listener = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
                if (runCatching { io.github.opencodeandroid.preview.PreviewTarget.forPort(listener.localPort) }.isSuccess) return listener
                listener.close()
            }
        }
        validListener().use { listener ->
            val port = listener.localPort
            io.github.opencodeandroid.preview.PreviewTarget.forPort(port)
            val client = command("/android-runtime/bin/android-preview", port.toString())
            ui.waitUntil(5000) { AndroidBridge.pending.value.isNotEmpty() }
            assertTrue(client.process.isAlive)
            assertTrue(Intents.getIntents().none { it.component?.className == io.github.opencodeandroid.preview.PreviewActivity::class.java.name })
            button("打开预览")
            // Advancing the Compose test clock is necessary for the asynchronous TCP probe
            // and resumed-lifecycle dispatch; blocking on the Linux child first freezes it.
            ui.waitUntil(10000) { Intents.getIntents().any { it.component?.className == io.github.opencodeandroid.preview.PreviewActivity::class.java.name } }
            assertEquals("http://127.0.0.1:$port/", client.finish().trim())
            Intents.intended(hasComponent(io.github.opencodeandroid.preview.PreviewActivity::class.java.name))
            ui.waitUntil(3000) { AndroidBridge.pending.value.isEmpty() }
        }
    }
    @Test fun clipboardRoundTripIsExplicitAndCancellationReturns130() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        var original: android.content.ClipData? = null
        ui.runOnIdle { original = clipboard.primaryClip }
        try {
            val writer = command("/android-runtime/bin/android-clipboard", "set", "BRIDGE_CLIPBOARD_FIXTURE")
            button("复制到剪贴板"); writer.finish()
            val reader = command("/android-runtime/bin/android-clipboard", "get")
            button("允许读取")
            assertEquals("BRIDGE_CLIPBOARD_FIXTURE", reader.finish().trim())
            val cancelled = command("/android-runtime/bin/android-open", "https://example.test/cancelled")
            button("取消"); cancelled.finish(130)
        } finally { ui.runOnIdle { original?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() } }
    }
    @Test fun invalidTokenAndUnsupportedSchemeNeverReachUi() {
        val config = JSONObject(File(paths.config, "bridge-auth.json").readText())
        fun rejected(token: String, url: String): JSONObject {
            return LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(config.getString("socket"), LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 3000
                val request = JSONObject().put("version", 1).put("token", token).put("action", "open-url").put("payload", JSONObject().put("url", url))
                socket.outputStream.write((request.toString() + "\n").toByteArray()); socket.outputStream.flush()
                JSONObject(socket.inputStream.bufferedReader().readLine())
            }
        }
        assertEquals("error", rejected("invalid", "https://example.test/").getString("status"))
        assertEquals("error", rejected(config.getString("token"), "intent://settings").getString("status"))
        assertEquals("error", rejected(config.getString("token"), "https://user:secret@example.test/").getString("status"))
        assertTrue(AndroidBridge.pending.value.isEmpty())
        val probe = context.contentResolver.call(Uri.parse("content://io.github.opencodeandroid.test.control"), "probeBridgePeer", config.getString("socket"), null)!!
        assertTrue(probe.getString("result"), probe.getString("result") in setOf("unauthorized_peer", "transport_denied"))
        assertTrue(AndroidBridge.pending.value.isEmpty())
    }
    @Test fun safImportExportAndSharingCompleteRealLinuxHelperRequests() {
        Intents.init(); intents = true
        val name = ".bridge-files-${java.util.UUID.randomUUID()}"
        val directory = File(paths.workspace, name).apply { check(mkdir()) }
        try {
            context.contentResolver.call(Uri.parse("content://io.github.opencodeandroid.test.control"), "grantFixture", context.packageName, null)
            val input = Uri.parse("content://io.github.opencodeandroid.test.upload/note.txt")
            val output = Uri.parse("content://io.github.opencodeandroid.test.upload/export.txt")
            Intents.intending(hasAction(Intent.ACTION_OPEN_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
            Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(output).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)))
            Intents.intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            val importer = command("/android-runtime/bin/android-import", "/workspace/$name")
            button("选择文件")
            assertTrue(importer.finish().contains("/workspace/$name/note.txt"))
            val text = File(directory, "note.txt")
            assertEquals("UPLOAD_DOCUMENT_OK", text.readText())
            text.writeText("EXPORTED_THROUGH_ANDROID_SAF")
            val exporter = command("/android-runtime/bin/android-export", "/workspace/$name/note.txt")
            button("选择保存位置"); exporter.finish()
            assertEquals("EXPORTED_THROUGH_ANDROID_SAF", context.contentResolver.openInputStream(output)!!.bufferedReader().use { it.readText() })
            val share = command("/android-runtime/bin/android-share", "/workspace/$name/note.txt")
            button("选择分享应用")
            ui.waitUntil(10000) { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
            share.finish()
            Intents.intended(hasAction(Intent.ACTION_CHOOSER))
        } finally {
            check(directory.canonicalFile.parentFile == paths.workspace.canonicalFile)
            java.nio.file.Files.walk(directory.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }
    @Test fun disconnectedHelperRemovesPendingRequestAndIndependentLeaseKeepsServer() {
        AndroidBridge.acquire(context, "second-test-owner")
        try {
            AndroidBridge.release("bridge-tests")
            val client = command("/android-runtime/bin/android-open", "https://example.test/disconnected")
            ui.waitUntil(5000) { AndroidBridge.pending.value.isNotEmpty() }
            client.process.destroyForcibly(); client.process.waitFor(3, TimeUnit.SECONDS)
            ui.waitUntil(5000) { AndroidBridge.pending.value.isEmpty() }
            assertTrue(File(paths.config, "bridge-auth.json").isFile)
        } finally { AndroidBridge.release("second-test-owner") }
        assertFalse(File(paths.config, "bridge-auth.json").exists())
        ui.waitUntil(3000) { Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "android-bridge-listener" } }
    }
    @Test fun backgroundRequestWaitsForResumedActivityAndExplicitAction() {
        Intents.init(); intents = true
        Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        try {
            assertFalse(AndroidBridge.foreground)
            val client = command("/android-runtime/bin/android-open", "https://example.test/background")
            ui.waitUntil(5000) { AndroidBridge.pending.value.isNotEmpty() }
            assertTrue(Intents.getIntents().none { it.action == Intent.ACTION_VIEW })
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            button("取消"); client.finish(130)
        } finally { ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
    }
    @Test fun approvedNotificationAppearsWithoutLeakingRequestToOtherApps() {
        org.junit.Assume.assumeTrue("Notification permission is a user setting", android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        val title = "Bridge acceptance ${java.util.UUID.randomUUID()}"
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        try {
            val client = command("/android-runtime/bin/android-notify", title, "Local fixture")
            button("发送通知"); client.finish()
            ui.waitUntil(5000) { manager.activeNotifications.any { it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == title } }
        } finally { manager.activeNotifications.filter { it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == title }.forEach { manager.cancel(it.id) } }
    }
}
