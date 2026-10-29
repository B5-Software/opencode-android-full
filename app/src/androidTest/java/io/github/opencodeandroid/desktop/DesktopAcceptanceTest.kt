package io.github.opencodeandroid.desktop

import io.github.opencodeandroid.ui.nativeLabel

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.ProotCommand
import io.github.opencodeandroid.runtime.NativeProcess
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class DesktopAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val includeBrowser = InstrumentationRegistry.getArguments().getString("includeChromiumGui") == "true"
    @Test fun authenticatedDesktopRendersAndSurvivesPageSwitch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Install desktop components first", DesktopState.available(RuntimePaths(context)))
        var chromium: NativeProcess? = null
        var browserOutput: Thread? = null
        try {
            context.startForegroundService(Intent(context, DesktopService::class.java).setAction(DesktopService.START).putExtra("resolution", "720x1280"))
            ui.waitUntil(150000) { DesktopState.current.value.connected || DesktopState.current.value.phase == "需要处理" }
            assertTrue(DesktopState.current.value.log, DesktopState.current.value.connected)
            if (includeBrowser) {
                // This local data-URL probe never enables the user's compatibility setting.
                val page = "data:text/html,<html><head><title>OpenCode Local Desktop Check</title></head><body style='background:%23174b32;color:white;font:36px sans-serif;padding:30px'><h1>Chromium on Debian</h1><p>Local Android desktop test</p></body></html>"
                val child = ProotCommand(RuntimePaths(context), 1000).process(listOf("/usr/bin/chromium", "--no-sandbox", "--disable-dev-shm-usage", "--disable-background-networking", "--disable-component-update", "--disable-sync", "--no-first-run", "--start-maximized", "--user-data-dir=/desktop-state/chromium-gui-probe", "--app=$page"), mapOf("DISPLAY" to ":1", "XDG_RUNTIME_DIR" to "/desktop-state/run")).start()
                chromium = child
                browserOutput = Thread { try { child.inputStream.use { input -> val buffer = ByteArray(8192); while (input.read(buffer) >= 0) { } } } catch (_: java.io.IOException) { } }.apply { start() }
            }
            ui.onNodeWithTag("workspace-orb").performClick()
            ui.onNodeWithTag("workspace-destination-2").performClick()
            awaitDesktop()
            val originalMode = File(RuntimePaths(context).desktop, "chromium-compat-enabled").isFile
            val position = AtomicReference<org.json.JSONObject>()
            ui.runOnIdle { browser(ui.activity.window.decorView)!!.evaluateJavascript("JSON.stringify((()=>{const r=document.getElementById('browser-options').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth}})())") { position.set(org.json.JSONObject(org.json.JSONTokener(it).nextValue() as String)) } }
            ui.waitUntil(3000) { position.get() != null }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(WebView::class.java)).perform(
                androidx.test.espresso.action.GeneralClickAction(androidx.test.espresso.action.Tap.SINGLE, { view ->
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    val bounds = position.get(); val scale = view.width / bounds.getDouble("width")
                    floatArrayOf((location[0] + bounds.getDouble("x") * scale).toFloat(), (location[1] + bounds.getDouble("y") * scale).toFloat())
                }, androidx.test.espresso.action.Press.FINGER))
            ui.waitUntil(5000) { ui.onAllNodesWithText(nativeLabel("Chromium 浏览器模式")).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(nativeLabel("取消")).performClick()
            assertEquals(originalMode, File(RuntimePaths(context).desktop, "chromium-compat-enabled").isFile)
            val directory = File(context.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
            ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(directory, "debian-desktop.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            ui.onNodeWithTag("workspace-orb").performClick()
            ui.onNodeWithTag("workspace-destination-3").performClick()
            assertTrue(DesktopState.current.value.connected)
            ui.onNodeWithTag("workspace-orb").performClick()
            ui.onNodeWithTag("workspace-destination-2").performClick()
            awaitDesktop()
            ui.runOnIdle { ui.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            awaitDesktop()
            assertTrue(DesktopState.current.value.connected)
        } finally {
            chromium?.destroyForcibly(); chromium?.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); browserOutput?.join(3000)
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", DesktopState.current.value.log.takeLast(12000)) })
            context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP))
            ui.waitUntil(15000) { !DesktopState.current.value.busy }
        }
    }

    private fun awaitDesktop() {
        val report = AtomicReference<String>()
        try {
            ui.waitUntil(60000) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    browser(ui.activity.window.decorView)?.evaluateJavascript("""JSON.stringify((()=>{const canvas=document.querySelector('canvas');const colors=new Set();let green=0;if(canvas?.width){const pixels=canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data;for(let y=0;y<canvas.height;y+=Math.max(1,Math.floor(canvas.height/40)))for(let x=0;x<canvas.width;x+=Math.max(1,Math.floor(canvas.width/40))){const i=(y*canvas.width+x)*4;colors.add(pixels[i]+','+pixels[i+1]+','+pixels[i+2]);if(pixels[i]<60&&pixels[i+1]>55&&pixels[i+2]<85&&pixels[i+1]>pixels[i]+15)green++}}const screen=document.getElementById('screen');return {connected:window.desktopConnected===true,green,colors:colors.size,width:canvas?.width||0,height:canvas?.height||0,targetWidth:screen?.clientWidth,targetHeight:screen?.clientHeight,status:document.getElementById('status')?.textContent}})())""") { report.set(it) }
                }
                val result = runCatching { org.json.JSONObject(org.json.JSONTokener(report.get() ?: "null").nextValue() as String) }.getOrNull()
                result?.optBoolean("connected") == true && result.optInt("width") > 100 && result.optInt("colors") > 8 && (!includeBrowser || result.optInt("green") > 100)
            }
            val frame = java.util.concurrent.CountDownLatch(1)
            ui.runOnIdle { browser(ui.activity.window.decorView)!!.postVisualStateCallback(2, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { frame.countDown() } }) }
            assertTrue(frame.await(5, java.util.concurrent.TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", "Desktop frame: ${report.get()}") })
        } catch (failure: ComposeTimeoutException) { throw AssertionError("${report.get()}\n${DesktopState.current.value.log}", failure) }
    }
    private fun browser(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) browser(view.getChildAt(index))?.let { return it }
        return null
    }
}
