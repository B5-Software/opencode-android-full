package io.github.opencodeandroid.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.AdaptiveWorkspace
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.OpenCodeAppTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real system IME, actual production container, synthetic local page only. */
class ImeViewportTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun keyboardTransitionsDoNotContinuouslyResizeTheChromiumViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sizes = CopyOnWriteArrayList<Int>()
        lateinit var browser: WebView
        var loaded = false
        ui.runOnUiThread {
            ui.activity.setContent { OpenCodeAppTheme {
                AdaptiveWorkspace(0, {}, false, false, {}, Modifier.fillMaxSize()) { page ->
                    if (page == 0) AndroidView(factory = { context ->
                        object : WebView(context) {
                            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                                super.onSizeChanged(w, h, oldw, oldh)
                                if (h != oldh) sizes += h
                            }
                        }.apply {
                            browser = this
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            settings.javaScriptEnabled = true
                            webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String) { loaded = true } }
                            val rows = (1..1800).joinToString("") { "<div>synthetic renderer fixture row $it</div>" }
                            loadDataWithBaseURL("https://appassets.androidplatform.net/ime-fixture", "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{margin:0}input{position:fixed;bottom:8px;left:8px;width:80%}</style>$rows<input id='fixture'>", "text/html", "UTF-8", null)
                        }
                    }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() })
                }
            } }
        }
        ui.waitUntil(20_000) { loaded }; ui.waitForIdle()
        val report = JSONArray()
        fun keyboardVisible(): Boolean {
            var shown = false
            instrumentation.runOnMainSync { shown = ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return shown
        }
        fun awaitVisible(expected: Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (keyboardVisible() != expected) { check(SystemClock.elapsedRealtime() < deadline) { "System IME did not reach requested state" }; Thread.sleep(40) }
            Thread.sleep(700); ui.waitForIdle()
        }
        try {
            repeat(4) { cycle ->
                sizes.clear()
                instrumentation.runOnMainSync {
                    browser.requestFocus()
                    browser.evaluateJavascript("document.getElementById('fixture').focus()") {
                        ui.activity.getSystemService(InputMethodManager::class.java).showSoftInput(browser, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                awaitVisible(true)
                val opened = sizes.toList(); sizes.clear()
                instrumentation.runOnMainSync { ui.activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(browser.windowToken, 0) }
                awaitVisible(false)
                report.put(JSONObject().put("cycle", cycle).put("openViewportHeights", JSONArray(opened)).put("closeViewportHeights", JSONArray(sizes.toList())))
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("imeViewportSamples", report.toString()) })
            if (InstrumentationRegistry.getArguments().getString("baselineIme") != "true") for (index in 0 until report.length()) {
                val sample = report.getJSONObject(index)
                assertTrue("Repeated viewport changes during keyboard opening", sample.getJSONArray("openViewportHeights").length() <= 3)
                assertTrue("Repeated viewport changes during keyboard closing", sample.getJSONArray("closeViewportHeights").length() <= 3)
            }
        } finally { instrumentation.runOnMainSync { ui.activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(browser.windowToken, 0) } }
    }
}
