package io.github.opencodeandroid.runtime

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.opencodeandroid.OfficialOpenCodeView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.webkit.CookieManager

@RunWith(AndroidJUnit4::class)
class OpenCodeAcceptanceTest {
    @Test fun officialDevServiceAuthenticatesAndPairs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        if (!paths.installed) RootfsInstaller(context, paths).install(::println)
        val expected = RootfsInstaller(context, paths).ensureOpenCode(::println)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        // This test controls when the WebView consumes a one-use link. The normal workspace now opens
        // automatically when the service becomes ready, so remove it before the raw endpoint assertions.
        instrumentation.runOnMainSync { activity.setContent {} }
        try {
            context.startForegroundService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.START))
            val deadline = System.currentTimeMillis() + 120_000
            while (RuntimeState.current.value.pairingUrl == null && System.currentTimeMillis() < deadline) {
                if (RuntimeState.current.value.phase == "Needs attention") break
                Thread.sleep(250)
            }
            val snapshot = RuntimeState.current.value
            val pairing = snapshot.pairingUrl
            assertNotNull("Server failed: ${snapshot.log}", pairing)
            val client = LocalOpenCode(CredentialVault(paths).password())
            assertTrue(client.ready(expected))
            val browserLink = client.pairingUrl()
            RuntimeState.paired(browserLink)
            val request = URL(browserLink).openConnection() as HttpURLConnection
            request.instanceFollowRedirects = false
            request.setRequestProperty("Accept", "text/html")
            request.connectTimeout = 3000; request.readTimeout = 3000
            try {
                assertEquals(302, request.responseCode)
                val cookie = request.getHeaderField("Set-Cookie")
                assertNotNull("Pairing must issue the official session cookie", cookie)
                assertTrue(cookie.contains("opencode_session_${LocalOpenCode.PORT}="))
            } finally { request.disconnect() }
            val replay = URL(browserLink).openConnection() as HttpURLConnection
            replay.instanceFollowRedirects = false
            replay.setRequestProperty("Accept", "application/json")
            try { assertEquals("Pairing links must be single use", 401, replay.responseCode) }
            finally { replay.disconnect() }
            val page = URL(LocalOpenCode.ORIGIN).readText()
            assertTrue("Official WebUI HTML is missing", page.contains("id=\"root\""))
            val corruptCookie = InstrumentationRegistry.getArguments().getString("staleCookie") == "true"
            if (corruptCookie) {
                assertEquals("Stale-cookie acceptance uses the isolated WebView profile", "true", InstrumentationRegistry.getArguments().getString("isolatedBrowser"))
                val set = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(LocalOpenCode.ORIGIN,
                        "opencode_session_${LocalOpenCode.PORT}=invalid-test-session; Path=/; HttpOnly; Max-Age=3600") { set.countDown() }
                }
                assertTrue(set.await(5, TimeUnit.SECONDS))
                assertFalse(LocalOpenCode.browserReady(CookieManager.getInstance().getCookie(LocalOpenCode.ORIGIN).orEmpty()))
            }
            // The RuntimeState link has already been consumed above. A stale browser must request a new link
            // from the running service, without restarting the backend or retaining a broken login screen.
            instrumentation.runOnMainSync { activity.setContent {
                val runtime by RuntimeState.current.collectAsState()
                runtime.pairingUrl?.let { url -> OfficialOpenCodeView(url, Modifier.fillMaxSize(), onReconnect = {
                    context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.RECONNECT))
                }) }
            } }
            fun findWebView(view: View): WebView? {
                if (view is WebView) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
                return null
            }
            val rootHeight = AtomicInteger(0)
            val renderDeadline = System.currentTimeMillis() + 20_000
            while (rootHeight.get() < 100 && System.currentTimeMillis() < renderDeadline) {
                instrumentation.runOnMainSync {
                    findWebView(activity.window.decorView)?.evaluateJavascript("(document.getElementById('root')?.clientHeight || 0)") { rootHeight.set(it.toIntOrNull() ?: 0) }
                }
                Thread.sleep(250)
            }
            assertTrue("Official WebUI viewport collapsed (${rootHeight.get()}px)", rootHeight.get() > 100)
            val browserStatus = AtomicInteger(0)
            val connectionDeadline = System.currentTimeMillis() + 20_000
            while (browserStatus.get() != 200 && System.currentTimeMillis() < connectionDeadline) {
                instrumentation.runOnMainSync {
                    findWebView(activity.window.decorView)?.evaluateJavascript("""
                        (()=>{fetch('/api/info',{credentials:'include',cache:'no-store',headers:{Accept:'application/json'}})
                          .then(r=>document.documentElement.dataset.connectionProbe=String(r.status));
                          return Number(document.documentElement.dataset.connectionProbe||0)})()
                    """.trimIndent()) { browserStatus.set(it.toIntOrNull() ?: 0) }
                }
                Thread.sleep(150)
            }
            assertEquals("Official WebUI is rendered but its own authenticated API is disconnected", 200, browserStatus.get())
            assertTrue(LocalOpenCode.browserReady(CookieManager.getInstance().getCookie(LocalOpenCode.ORIGIN).orEmpty()))
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream",
                "\nPASS official WebUI cookie-authenticated API=200, including ${if (corruptCookie) "stale HttpOnly cookie and consumed-link automatic recovery" else "native session bootstrap"}.\n") })
            println("PASS official dev $expected: auth, pairing, single-use links, WebView CSS viewport ${rootHeight.get()}px")
            if (InstrumentationRegistry.getArguments().getString("keepRunning") == "true") RuntimeState.paired(client.pairingUrl())
        } finally {
            if (InstrumentationRegistry.getArguments().getString("keepRunning") != "true") {
                context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
