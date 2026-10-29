package io.github.opencodeandroid.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.R
import io.github.opencodeandroid.WebViewRendering
import io.github.opencodeandroid.runtime.LocalOpenCode
import io.github.opencodeandroid.runtime.RuntimeState
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeRenderingTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @After fun reset() { ui.runOnIdle { RuntimeState.paired(null) } }
    private fun select(page: Int) {
        ui.onNodeWithTag("workspace-orb").performClick()
        ui.onNodeWithTag("workspace-destination-$page").performClick()
    }
    private fun browser(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) browser(root.getChildAt(index))?.let { return it }
        return null
    }
    private fun script(view: WebView, code: String): String {
        val result = AtomicReference<String>()
        instrumentation.runOnMainSync { view.evaluateJavascript(code) { result.set(it) } }
        ui.waitUntil(10000) { result.get() != null }
        return result.get()
    }
    @Test fun switchingTabsAndRotatingRetainLiveDocument() {
        UiFixtureServer().use {
            ui.runOnIdle { RuntimeState.paired(LocalOpenCode.ORIGIN); ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            select(0)
            var view: WebView? = null
            ui.waitUntil(10000) { instrumentation.runOnMainSync { view = browser(ui.activity.window.decorView) }; view != null }
            val original = requireNotNull(view)
            // Supply this synthetic document directly: a previously installed official service
            // worker can intercept every HTTP navigation in the reusable validation profile.
            ui.runOnIdle {
                original.loadDataWithBaseURL(LocalOpenCode.ORIGIN + "/native-retention-fixture", "<!doctype html><title>NATIVE-RETENTION</title><p>Native retention fixture</p>", "text/html", "UTF-8", null)
            }
            try {
                ui.waitUntil(10000) { var ready = false; instrumentation.runOnMainSync { ready = original.title == "NATIVE-RETENTION" }; ready }
            } catch (failure: ComposeTimeoutException) {
                var details = ""
                instrumentation.runOnMainSync { details = "sameView=${original === browser(ui.activity.window.decorView)}, title=${original.title}, paired=${RuntimeState.current.value.pairingUrl != null}, phase=${RuntimeState.current.value.phase}" }
                throw AssertionError("Fixture did not load: $details", failure)
            }
            assertEquals("\"retained\"", script(original, "window.__nativeRetentionFixture = 'retained'"))
            val activity = ui.activity
            select(4)
            ui.runOnIdle { assertNull(original.parent); assertEquals(View.GONE, original.visibility) }
            select(0)
            ui.runOnIdle { assertSame(original, browser(ui.activity.window.decorView)); ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            ui.runOnIdle { assertSame(activity, ui.activity); assertSame(original, browser(ui.activity.window.decorView)) }
            assertEquals("\"retained\"", script(original, "window.__nativeRetentionFixture"))
            // Memory pressure deliberately drops only the inactive page; the next visit recovers.
            select(4)
            ui.runOnIdle { ui.activity.retainedBrowser.trim() }
            select(0)
            ui.runOnIdle { assertNotSame(original, browser(ui.activity.window.decorView)) }
        }
    }
    @Test fun opacitySliderPersistsAcrossActivityRecreation() {
        val preferences = ui.activity.getSharedPreferences("workspace-navigation", Context.MODE_PRIVATE)
        val original = preferences.all["orb-opacity"] as? Float
        try {
            ui.onNodeWithTag("workspace-orb").performClick()
            ui.onNodeWithTag("orb-opacity").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
            ui.onNodeWithText(nativeLabel("悬浮球不透明度 40%")).assertIsDisplayed()
            ui.runOnIdle { assertEquals(.4f, preferences.getFloat("orb-opacity", 1f), .001f) }
            ui.activityRule.scenario.recreate()
            ui.waitForIdle()
            if (ui.onAllNodesWithTag("workspace-menu").fetchSemanticsNodes().isEmpty()) ui.onNodeWithTag("workspace-orb").performClick()
            ui.onNodeWithTag("orb-opacity").performScrollTo()
            ui.onNodeWithText(nativeLabel("悬浮球不透明度 40%")).assertIsDisplayed()
        } finally {
            preferences.edit().apply { if (original == null) remove("orb-opacity") else putFloat("orb-opacity", original) }.commit()
        }
    }
    @Test fun hostThemeSetsWebViewSystemColorSchemeWithoutPageInjection() {
        for (dark in listOf(false, true)) {
            val result = AtomicReference<String>()
            val completed = CountDownLatch(1)
            var browser: WebView? = null
            try {
                instrumentation.runOnMainSync {
                    val configuration = Configuration(ui.activity.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val themed = ContextThemeWrapper(ui.activity.createConfigurationContext(configuration), R.style.Theme_OpenCodeAndroid)
                    val value = TypedValue()
                    assertTrue(themed.theme.resolveAttribute(android.R.attr.isLightTheme, value, true))
                    assertEquals(!dark, value.data != 0)
                    browser = WebView(themed).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript("matchMedia('(prefers-color-scheme: dark)').matches") { result.set(it); completed.countDown() }
                            }
                        }
                        loadDataWithBaseURL("http://127.0.0.1/native-theme-fixture", "<title>Native theme fixture</title>", "text/html", "UTF-8", null)
                    }
                }
                assertTrue(completed.await(15, TimeUnit.SECONDS))
                assertEquals(dark.toString(), result.get())
            } finally { instrumentation.runOnMainSync { browser?.destroy() } }
        }
    }
    @Test fun renderingPreferenceChangesOnlyThisWindow() {
        val context = ui.activity
        val preferences = context.getSharedPreferences(WebViewRendering.PREFERENCES, 0)
        val original = preferences.all["balanced"] as? Boolean
        try {
            for (balanced in listOf(true, false)) {
                ui.runOnIdle { WebViewRendering.setBalanced(context, balanced) }
                ui.waitForIdle()
                ui.runOnIdle {
                    assertEquals(if (balanced) 60f else 0f, context.window.attributes.preferredRefreshRate, .001f)
                    if (Build.VERSION.SDK_INT >= 35) assertEquals(if (balanced) 60f else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT, context.window.decorView.requestedFrameRate, .001f)
                }
                val complete = CountDownLatch(1)
                var frames = 0
                val start = android.os.SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync {
                    val scheduler = android.view.Choreographer.getInstance()
                    scheduler.postFrameCallback(object : android.view.Choreographer.FrameCallback {
                        override fun doFrame(time: Long) {
                            frames++
                            if (android.os.SystemClock.elapsedRealtime() - start < 1000) scheduler.postFrameCallback(this) else complete.countDown()
                        }
                    })
                }
                assertTrue(complete.await(5, TimeUnit.SECONDS))
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nNative frame scheduling: balanced=$balanced, callbacks=$frames per ~1 second. This is window scheduling, not a page scrolling benchmark.\n") })
            }
        } finally { preferences.edit().apply { if (original == null) remove("balanced") else putBoolean("balanced", original) }.commit() }
    }
}
