package io.github.opencodeandroid.ui

import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.OfficialResourceCache
import io.github.opencodeandroid.runtime.LocalOpenCode
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OfficialResourceCacheTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun script(view: WebView, source: String): String {
        val result = AtomicReference<String>(); val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { view.evaluateJavascript(source) { result.set(it); latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)); return result.get()
    }
    private fun waitScript(view: WebView, expression: String) {
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) { if (script(view, expression) == "true") return; Thread.sleep(50) }
        fail("Native fixture asynchronous operation did not finish: " + script(view, "JSON.stringify({error:window.seedError||null,secure:isSecureContext,worker:'serviceWorker' in navigator,caches:'caches' in window})"))
    }
    @Test fun transitionClearsOldWorkerAndResourceCachesWhilePreservingUserStorage() {
        check(InstrumentationRegistry.getArguments().getString("isolatedBrowser") == "true")
        UiFixtureServer().use {
            val key = "fixture-${UUID.randomUUID()}"; val user = "user-$key"; val resource = "workbox-precache-$key"
            var browser: WebView? = null
            val loaded = CountDownLatch(1)
            try {
                instrumentation.runOnMainSync {
                    browser = WebView(instrumentation.targetContext).apply {
                        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() } }
                        // Registration requires a real HTTP document; the official worker excludes /auth/.
                        loadUrl(LocalOpenCode.ORIGIN + "/auth/native-cache-fixture")
                    }
                }
                check(loaded.await(10, TimeUnit.SECONDS)); val view = requireNotNull(browser)
                script(view, """
                    (async()=>{
                      localStorage.setItem('$user','preserved'); document.cookie='$user=preserved; Path=/';
                      await caches.open('$resource');await caches.open('$user');
                      const registration=await navigator.serviceWorker.register('/native-cache-worker.js');
                      await navigator.serviceWorker.ready;window.seeded=true;
                    })().catch(error=>window.seedError=error.name);void 0
                """.trimIndent())
                waitScript(view, "window.seeded===true")
                val done = CountDownLatch(1); var succeeded = false
                instrumentation.runOnMainSync { OfficialResourceCache.prepare(view, key, ready = { succeeded = true; done.countDown() }, failed = { done.countDown() }) }
                check(done.await(15, TimeUnit.SECONDS)); assertTrue("Native resource transition succeeded: " + script(view, "JSON.stringify({state:window.__opencodeNativeResourceTransition,error:window.__opencodeNativeResourceTransitionError,marker:!!document.querySelector('meta[name=opencode-native-transition]'),secure:isSecureContext})"), succeeded)
                script(view, """
                    (async()=>{
                      const names=await caches.keys();
                      window.checked=(!names.includes('$resource')&&names.includes('$user')&&names.includes('opencode-android-resources-$key')&&(await navigator.serviceWorker.getRegistrations()).length===0&&localStorage.getItem('$user')==='preserved');
                    })();void 0
                """.trimIndent())
                waitScript(view, "window.checked===true")
                assertTrue(script(view, "document.cookie.includes('$user=preserved')") == "true")
                // Re-entering the same version keeps its resource cache; there is no cold rebuild on tabs.
                script(view, "window.seeded=false;caches.open('$resource').then(()=>window.seeded=true);void 0")
                waitScript(view, "window.seeded===true")
                val again = CountDownLatch(1)
                instrumentation.runOnMainSync { OfficialResourceCache.prepare(view, key, ready = { again.countDown() }, failed = { fail("same-version preparation failed") }) }
                check(again.await(15, TimeUnit.SECONDS))
                script(view, "caches.keys().then(names=>window.stillCached=names.includes('$resource'));void 0")
                waitScript(view, "window.stillCached===true")
            } finally {
                browser?.let { view ->
                    runCatching { script(view, "localStorage.removeItem('$user');document.cookie='$user=; Max-Age=0; Path=/';if('caches' in window){caches.delete('$user');caches.delete('$resource');caches.delete('opencode-android-resources-$key');}if('serviceWorker' in navigator){navigator.serviceWorker.getRegistrations().then(items=>Promise.all(items.map(item=>item.unregister())));}void 0") }
                    instrumentation.runOnMainSync { view.stopLoading(); view.destroy() }
                }
            }
        }
    }
}
