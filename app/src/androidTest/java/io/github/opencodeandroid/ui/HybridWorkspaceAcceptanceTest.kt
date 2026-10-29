package io.github.opencodeandroid.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.WorkspaceLayoutStore
import io.github.opencodeandroid.WorkspaceMode
import io.github.opencodeandroid.desktop.DesktopService
import io.github.opencodeandroid.desktop.DesktopState
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.RuntimeService
import io.github.opencodeandroid.runtime.RuntimeState
import io.github.opencodeandroid.runtime.ProotCommand
import io.github.opencodeandroid.runtime.NativeProcess
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real official WebUI and XFCE framebuffer in the same unmodified production workspace on K30. */
class HybridWorkspaceAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun officialCodeAndAuthenticatedDesktopRenderTogetherWithoutRestartingServices() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RuntimePaths(context).installed)
        assertTrue("Desktop components must be installed for this acceptance", DesktopState.available(RuntimePaths(context)))
        val preferences = context.getSharedPreferences("workspace-layout", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        var chromium: NativeProcess? = null
        var browserOutput: Thread? = null
        try {
            ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            val width = ui.activity.resources.displayMetrics.let { it.widthPixels / it.density }
            assertTrue("Real available landscape width $width dp is below the expanded threshold", width >= 840)
            // Change only layout preferences; the runner's isolated browser retains the user's real profile.
            listOf("expanded-short", "expanded-tall", "large-short", "large-tall").forEach { WorkspaceLayoutStore(preferences).mode(it, WorkspaceMode.Hybrid) }
            // Apply the saved mode through an actual Activity recreation, as happens after rotation.
            ui.activityRule.scenario.recreate()
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.START))
            ui.waitUntil(120000) { RuntimeState.current.value.pairingUrl != null || RuntimeState.current.value.phase == "Needs attention" }
            assertNotNull(RuntimeState.current.value.log, RuntimeState.current.value.pairingUrl)
            context.startForegroundService(Intent(context, DesktopService::class.java).setAction(DesktopService.START).putExtra("resolution", "1280x720"))
            ui.waitUntil(150000) { DesktopState.current.value.connected || DesktopState.current.value.phase == "需要处理" }
            assertTrue(DesktopState.current.value.log, DesktopState.current.value.connected)
            // A local diagnostic page with an explicit test-only sandbox opt-out. This never enables
            // the user's compatibility preference and does not browse any external website.
            val page = "data:text/html,<html><head><title>OpenCode Hybrid Check</title></head><body style='background:%23174b32;color:white;font:32px sans-serif;padding:24px'><h1>Chromium on Debian</h1><p>OpenCode Hybrid Workspace</p></body></html>"
            val child = ProotCommand(RuntimePaths(context), 1000).process(listOf("/usr/bin/chromium", "--no-sandbox", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update", "--disable-sync", "--no-first-run", "--start-maximized", "--user-data-dir=/desktop-state/chromium-gui-probe", "--app=$page"), mapOf("DISPLAY" to ":1", "XDG_RUNTIME_DIR" to "/desktop-state/run")).start()
            chromium = child
            browserOutput = Thread { child.inputStream.bufferedReader().use { it.forEachLine { } } }.apply { start() }
            // Explicit navigation also exercises the native shortcut while a WebView owns focus.
            key(KeyEvent.KEYCODE_1)
            ui.onNodeWithTag("workspace-hybrid").assertExists()
            awaitCode()
            awaitDesktop()
            val code = find(49374)!!; val desktop = find(6080)!!
            val supervisors = activeSupervisors()
            assertTrue("Both services should have live supervisors", supervisors.size >= 2)
            val before = code.width
            ui.onNodeWithTag("workspace-divider-code-desktop").performTouchInput { swipe(center, center.copy(x = center.x + 120f), 500) }
            ui.runOnIdle {
                assertSame("Resizing must keep the official WebView", code, find(49374))
                assertSame("Resizing must keep the desktop WebView", desktop, find(6080))
                assertTrue(code.width > before + 50)
            }
            assertTrue(activeSupervisors().containsAll(supervisors))
            awaitDesktop()
            capture("hybrid-real-services.png")
            key(KeyEvent.KEYCODE_ENTER)
            ui.onNodeWithTag("workspace-single").assertExists()
            assertSame(code, find(49374))
            assertTrue(DesktopState.current.value.connected)
            key(KeyEvent.KEYCODE_ENTER)
            ui.onNodeWithTag("workspace-hybrid").assertExists()
            assertSame(code, find(49374))
            awaitDesktop()
            ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            ui.onNodeWithTag("workspace-single").assertExists()
            awaitCode()
            ui.runOnIdle { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            ui.onNodeWithTag("workspace-hybrid").assertExists()
            awaitCode(); awaitDesktop()
            assertTrue("Rotation must retain the original Linux supervisors", activeSupervisors().containsAll(supervisors))
        } finally {
            chromium?.destroyForcibly(); chromium?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); browserOutput?.join(3000)
            context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP))
            ui.waitUntil(20000) { !DesktopState.current.value.busy }
            context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
            ui.waitUntil(20000) { !RuntimeState.current.value.busy }
            ui.waitUntil(5000) { !File(RuntimePaths(context).config, "bridge-auth.json").exists() }
            val edit = preferences.edit()
            (preferences.all.keys + original.keys).filter { key -> key.substringBefore('.').startsWith("expanded-") || key.substringBefore('.').startsWith("large-") }.forEach { key ->
                when (val value = original[key]) { null -> edit.remove(key); is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value) }
            }
            edit.commit()
        }
    }

    private fun awaitCode() {
        ui.waitUntil(30000) {
            val view = find(49374) ?: return@waitUntil false
            val report = json(view, "JSON.stringify((()=>{if(!window.__adaptiveStatus)fetch('/api/info',{credentials:'include'}).then(r=>window.__adaptiveStatus=r.status);return {status:window.__adaptiveStatus||0,height:document.getElementById('root')?.getBoundingClientRect().height||0}})())")
            report?.optInt("status") == 200 && report.optDouble("height") > 100
        }
    }
    private fun awaitDesktop() {
        var last: JSONObject? = null
        ui.waitUntil(30000) {
            val view = find(6080) ?: return@waitUntil false
            val report = json(view, "JSON.stringify((()=>{const c=document.querySelector('canvas');return {connected:window.desktopConnected===true,width:c?.width||0,height:c?.height||0,targetWidth:document.getElementById('screen').clientWidth,targetHeight:document.getElementById('screen').clientHeight}})())")
            last = report
            report?.optBoolean("connected") == true && report.optInt("width") > 300
        }
        val visual = java.util.concurrent.CountDownLatch(1)
        ui.runOnIdle { find(6080)!!.postVisualStateCallback(7, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { visual.countDown() } }) }
        assertTrue("Desktop compositor did not commit its frame", visual.await(5, java.util.concurrent.TimeUnit.SECONDS))
        var lastFrame: Bitmap? = null
        val deadline = System.currentTimeMillis() + 15000
        var green = 0; var samples = 0
        do {
            lastFrame?.recycle(); lastFrame = pixelCopy()
            val rect = IntArray(4)
            ui.runOnIdle { find(6080)!!.let { view -> view.getLocationInWindow(rect); rect[2] = view.width; rect[3] = view.height } }
            green = 0; samples = 0
            for (y in (rect[1] + rect[3] * .45f).toInt() until (rect[1] + rect[3] * .72f).toInt() step 8)
                for (x in (rect[0] + rect[2] * .1f).toInt() until (rect[0] + rect[2] * .9f).toInt() step 8) {
                    val pixel = lastFrame.getPixel(x.coerceIn(0, lastFrame.width - 1), y.coerceIn(0, lastFrame.height - 1)); samples++
                    val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel)
                    if (r < 60 && g > 55 && b < 85 && g > r + 15) green++
                }
            if (green > samples / 4) break
            Thread.sleep(100)
        } while (System.currentTimeMillis() < deadline)
        if (green <= samples / 4) {
            val failure = File(ui.activity.getExternalFilesDir(null), "ui-checks/hybrid-window-failure.png")
            failure.outputStream().use { lastFrame!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        lastFrame?.recycle()
        assertTrue("Actual desktop window was not painted: green=$green/$samples $last", green > samples / 4)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "Hybrid actual frame: $last") })
    }
    private fun json(view: WebView, expression: String): JSONObject? {
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.evaluateJavascript(expression) { result.set(it) } }
        val until = System.currentTimeMillis() + 1000
        while (result.get() == null && System.currentTimeMillis() < until) Thread.sleep(20)
        return runCatching { JSONObject(JSONTokener(result.get()).nextValue() as String) }.getOrNull()
    }
    private fun find(port: Int): WebView? {
        var result: WebView? = null
        val inspect = {
            fun visit(view: View) {
                if (view is WebView && runCatching { Uri.parse(view.url).port }.getOrDefault(-1) == port) result = view
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(ui.activity.window.decorView)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) inspect()
        else InstrumentationRegistry.getInstrumentation().runOnMainSync(inspect)
        return result
    }
    private fun key(code: Int) { ui.runOnIdle { assertTrue(ui.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))) }; ui.waitForIdle() }
    private fun activeSupervisors() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.matches(Regex("guest-wait-[0-9]+")) }.map { it.name }.toSet()
    private fun capture(name: String) {
        val directory = File(ui.activity.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        val bitmap = pixelCopy()
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun pixelCopy(): Bitmap {
        val done = java.util.concurrent.CountDownLatch(1)
        val status = java.util.concurrent.atomic.AtomicInteger(-1)
        lateinit var bitmap: Bitmap
        ui.runOnIdle {
            val decor = ui.activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(ui.activity.window, bitmap, { status.set(it); done.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        assertTrue("Own-window PixelCopy timed out", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status.get())
        return bitmap
    }
}
