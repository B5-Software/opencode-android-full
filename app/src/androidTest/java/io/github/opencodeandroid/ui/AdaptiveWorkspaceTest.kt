package io.github.opencodeandroid.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.*
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Synthetic window constraints validate layout mechanics; real services have a separate acceptance test. */
class AdaptiveWorkspaceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun liveWebViewsAndDraftSurviveSplitMovesResizeAndMaximize() {
        val preferences = ui.activity.getSharedPreferences("adaptive-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val store = WorkspaceLayoutStore(preferences)
        val views = ConcurrentHashMap<Int, WebView>()
        val created = ConcurrentHashMap<Int, Int>()
        val released = ConcurrentHashMap<Int, Int>()
        var windowWidth by mutableFloatStateOf(1080f)
        var selected by mutableIntStateOf(0)
        ui.runOnUiThread { ui.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    var menu by remember { mutableStateOf(false) }
                    AdaptiveWorkspace(selected, { selected = it }, false, menu, { menu = it }, modifier = Modifier.width(windowWidth.dp), layoutStore = store) { page ->
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { context -> WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false; settings.allowContentAccess = false
                            created[page] = (created[page] ?: 0) + 1
                            views[page] = this
                            loadDataWithBaseURL("https://fixture.invalid/", "<html><body><input id='draft' value=''><script>window.fixtureReady=true</script></body></html>", "text/html", "UTF-8", null)
                        } }, onRelease = { released[page] = (released[page] ?: 0) + 1; it.destroy() })
                    }
                }
            }
        } }
        ui.onNodeWithTag("workspace-expanded").assertExists()
        ui.waitUntil(5000) { views[0] != null && views[1] != null && views[3] != null }
        val code = views[0]!!; val terminal = views[1]!!
        ui.waitUntil(5000) { javascript(code, "!!window.fixtureReady") == "true" }
        ui.runOnIdle {
            code.requestFocus()
            code.evaluateJavascript("document.getElementById('draft').value='中文草稿';document.getElementById('draft').focus();document.getElementById('draft').setSelectionRange(1,3);window.retainedMarker=73", null)
        }
        val initial = ui.onNodeWithTag("workspace-pane-3").fetchSemanticsNode().boundsInRoot.width
        ui.onNodeWithTag("workspace-divider-files").performTouchInput { swipe(center, center.copy(x = center.x + 100f), 500) }
        assertTrue("Dragging must resize the actual file pane", ui.onNodeWithTag("workspace-pane-3").fetchSemanticsNode().boundsInRoot.width > initial + 30f)
        assertTrue(store.profile("expanded-tall", WorkspaceMode.Develop).filesFraction > .24f)
        key(KeyEvent.KEYCODE_M)
        ui.onNodeWithTag("workspace-hybrid").assertExists()
        ui.onNodeWithTag("workspace-pane-2").assertExists()
        assertSame(code, views[0]); assertSame(terminal, views[1])
        assertEquals(1, created[0]); assertNull(released[0]); assertNull(released[1])
        key(KeyEvent.KEYCODE_ENTER)
        ui.onNodeWithTag("workspace-single").assertExists()
        assertSame(code, views[0]); assertEquals(1, created[0]); assertNull(released[0])
        key(KeyEvent.KEYCODE_ENTER)
        ui.onNodeWithTag("workspace-hybrid").assertExists()
        ui.runOnIdle { windowWidth = 380f }
        ui.onNodeWithTag("workspace-single").assertExists()
        assertSame(code, views[0]); assertEquals(1, created[0]); assertNull(released[0])
        ui.runOnIdle { windowWidth = 1080f }
        ui.onNodeWithTag("workspace-hybrid").assertExists()
        assertSame(code, views[0]); assertEquals(1, created[0]); assertNull(released[0])
        val result = JSONObject(JSONTokener(javascript(code, "JSON.stringify({draft:document.getElementById('draft').value,start:document.getElementById('draft').selectionStart,end:document.getElementById('draft').selectionEnd,marker:window.retainedMarker})")).nextValue() as String)
        assertEquals("中文草稿", result.getString("draft")); assertEquals(1, result.getInt("start")); assertEquals(3, result.getInt("end")); assertEquals(73, result.getInt("marker"))
        ui.onNodeWithTag("workspace-divider-code-desktop").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(.6f)) }
        assertEquals(.6f, store.profile("expanded-tall", WorkspaceMode.Hybrid).codeFraction, .001f)
        key(KeyEvent.KEYCODE_2)
        key(KeyEvent.KEYCODE_T)
        ui.onNodeWithTag("workspace-hybrid").assertExists()
        ui.onNodeWithTag("workspace-pane-1").assertDoesNotExist()
        assertEquals(0, selected)
        key(KeyEvent.KEYCODE_4)
        ui.onNodeWithTag("workspace-single").assertExists()
        key(KeyEvent.KEYCODE_B); key(KeyEvent.KEYCODE_B)
        ui.onNodeWithTag("workspace-hybrid").assertExists()
        assertEquals(0, selected)
        val directory = File(ui.activity.getExternalFilesDir(null), "ui-checks").apply { mkdirs() }
        ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap -> File(directory, "adaptive-fixture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test fun windowProfilesAreIndependentAndDamagedValuesDoNotBreakLayout() {
        val preferences = ui.activity.getSharedPreferences("adaptive-profile-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val store = WorkspaceLayoutStore(preferences)
        store.mode("large-tall", WorkspaceMode.Hybrid)
        store.profile("large-tall", WorkspaceMode.Hybrid, WorkspaceProfile(false, terminalRight = true, codeFraction = .61f))
        val restored = WorkspaceLayoutStore(preferences)
        assertEquals(WorkspaceMode.Hybrid, restored.mode("large-tall"))
        assertEquals(.61f, restored.profile("large-tall", WorkspaceMode.Hybrid).codeFraction, .001f)
        assertTrue(restored.profile("large-tall", WorkspaceMode.Hybrid).terminalRight)
        assertEquals(WorkspaceMode.Develop, restored.mode("expanded-short"))
        assertEquals(.5f, restored.profile("expanded-short", WorkspaceMode.Hybrid).codeFraction, .001f)
        preferences.edit().putFloat("large-tall.Hybrid.code-ratio", Float.NaN).putString("large-tall.Hybrid.files-ratio", "wrong-type").putString("large-tall.mode", "unknown-mode").commit()
        assertEquals(.5f, restored.profile("large-tall", WorkspaceMode.Hybrid).codeFraction, .001f)
        assertEquals(.24f, restored.profile("large-tall", WorkspaceMode.Hybrid).filesFraction, .001f)
        assertEquals(WorkspaceMode.Develop, restored.mode("large-tall"))
        assertEquals("compact-tall", workspaceSizeKey(839f, 900f)); assertEquals("expanded-short", workspaceSizeKey(840f, 393f)); assertEquals("large-tall", workspaceSizeKey(1200f, 900f))
        val altGr = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_RIGHT_ON)
        assertNull("AltGr text input must not become a workspace shortcut", WorkspaceCommand.from(altGr))
    }

    @Test fun resizingAndExtremeRatiosNeverOverlapPanesOrLoseWindowArea() {
        for (width in listOf(0f, 320f, 840f, 1120f, 1280f, 1600f)) for (height in listOf(0f, 180f, 393f, 600f, 900f))
            for (mode in WorkspaceMode.entries) for (selected in 0..4) for (ratio in listOf(-20f, .32f, 20f, Float.NaN)) {
                val arrangement = arrangeWorkspace(width, height, selected, 0, mode, WorkspaceProfile(true, terminalRight = true, filesFraction = ratio, codeFraction = ratio, terminalFraction = ratio), width >= 840f)
                val rectangles = arrangement.panes.values.toList() + arrangement.dividers.map { it.rect }
                rectangles.forEach { rect ->
                    assertTrue(rect.x.isFinite() && rect.y.isFinite() && rect.width.isFinite() && rect.height.isFinite())
                    assertTrue(rect.width >= 0f && rect.height >= 0f && rect.x >= 0f && rect.y >= 0f)
                    assertTrue(rect.x + rect.width <= width + .01f && rect.y + rect.height <= height + .01f)
                }
                for (a in rectangles.indices) for (b in a + 1 until rectangles.size) {
                    val first = rectangles[a]; val second = rectangles[b]
                    val overlapWidth = minOf(first.x + first.width, second.x + second.width) - maxOf(first.x, second.x)
                    val overlapHeight = minOf(first.y + first.height, second.y + second.height) - maxOf(first.y, second.y)
                    assertTrue("Overlapping panes at $width/$height/$mode", overlapWidth <= .01f || overlapHeight <= .01f)
                }
                assertEquals((width * height).toDouble(), rectangles.sumOf { (it.width * it.height).toDouble() }, maxOf(.1, width * height * .00001).toDouble())
            }
        val right = arrangeWorkspace(1600f, 900f, 0, 0, WorkspaceMode.Hybrid, WorkspaceProfile(true, terminalRight = true), true)
        assertEquals(setOf(0, 1, 2, 3), right.panes.keys)
        assertEquals(900f, right.panes.getValue(1).height)
        assertTrue(right.panes.getValue(1).x > right.panes.getValue(2).x)
    }

    private fun key(code: Int) {
        ui.runOnIdle { assertTrue(ui.activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))) }
        ui.waitForIdle()
    }

    private fun javascript(view: WebView, expression: String): String {
        val result = AtomicReference<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { view.evaluateJavascript(expression) { result.set(it) } }
        val until = System.currentTimeMillis() + 3000
        while (result.get() == null && System.currentTimeMillis() < until) Thread.sleep(20)
        return result.get() ?: error("WebView callback timed out")
    }
}
