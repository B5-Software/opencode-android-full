package io.github.opencodeandroid.runtime

import android.content.Intent
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.TerminalWorkspace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class TerminalAcceptanceTest {
    @Test fun trustedTerminalRendersPtyAndAcceptsKeyboardData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        if (!paths.installed) RootfsInstaller(context, paths).install(::println)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.START))
            await(100_000) { RuntimeState.current.value.pairingUrl != null || RuntimeState.current.value.phase == "Needs attention" }
            assertNotNull(RuntimeState.current.value.log, RuntimeState.current.value.pairingUrl)
            instrumentation.runOnMainSync { activity.setContent { TerminalWorkspace(true, Modifier.fillMaxSize()) } }
            val ready = AtomicBoolean(false)
            val browser = AtomicReference<WebView>()
            await(20_000) {
                instrumentation.runOnMainSync {
                    findWebView(activity.window.decorView)?.let { view ->
                        browser.set(view)
                        view.evaluateJavascript("typeof terminal !== 'undefined' && terminal.rows > 2 && terminal.cols > 2") { ready.set(it == "true") }
                    }
                }
                ready.get() && TerminalSessions.sessions.value.isNotEmpty()
            }
            val view = browser.get()
            assertNotNull("Terminal asset did not initialize", view)
            assertTrue("xterm and fit addon did not initialize", ready.get())
            instrumentation.runOnMainSync {
                assertFalse(view.settings.allowFileAccess)
                assertFalse(view.settings.allowContentAccess)
                assertTrue(view.url!!.startsWith("https://appassets.androidplatform.net/assets/terminal/"))
            }
            val first = TerminalSessions.sessions.value.first()
            val marker = "PTY_BRIDGE_OK"
            val encodedCommand = Base64.encodeToString(("printf '" + marker.toByteArray().joinToString("") { "\\x%02x".format(it) } + "\\n'; stty size\r").toByteArray(), Base64.NO_WRAP)
            val dimensions = AtomicReference<String>()
            instrumentation.runOnMainSync {
                view.evaluateJavascript("JSON.stringify([terminal.rows,terminal.cols])") { dimensions.set(it) }
                view.evaluateJavascript("terminal.input(atob(\"$encodedCommand\"), true)", null)
            }
            await(10_000) { first.snapshot().bytes.toString(Charsets.UTF_8).contains(marker) }
            assertTrue("JavaScript keyboard → PTY → xterm round trip failed", first.snapshot().bytes.toString(Charsets.UTF_8).contains(marker))
            // evaluateJavascript JSON-encodes a string result once more.
            val size = JSONArray(org.json.JSONTokener(dimensions.get()).nextValue() as String)
            await(5000) { first.snapshot().bytes.toString(Charsets.UTF_8).contains("${size.getInt(0)} ${size.getInt(1)}") }
            val text = first.snapshot().bytes.toString(Charsets.UTF_8)
            assertTrue("Terminal rows/columns did not reach Debian: $text", text.contains("${size.getInt(0)} ${size.getInt(1)}"))
            first.write("sleep 30\r".toByteArray())
            Thread.sleep(300)
            first.write(byteArrayOf(3))
            val afterInterrupt = "INTERRUPT_OK"
            first.write(("printf '" + afterInterrupt.toByteArray().joinToString("") { "\\x%02x".format(it) } + "\\n'\r").toByteArray())
            await(5000) { first.snapshot().bytes.toString(Charsets.UTF_8).contains(afterInterrupt) }
            assertTrue("Ctrl+C did not stop the foreground command", first.snapshot().bytes.toString(Charsets.UTF_8).contains(afterInterrupt))
            TerminalSessions.open()
            await(5000) { TerminalSessions.sessions.value.size == 2 }
            assertEquals(2, TerminalSessions.sessions.value.size)
            assertNotEquals(first.id, TerminalSessions.sessions.value.last().id)
            println("PASS terminal: bundled xterm, JS input bridge, controlling PTY, resize, Ctrl+C, multiple sessions")
        } finally {
            context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(timeout: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }
}
