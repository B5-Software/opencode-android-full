package io.github.opencodeandroid

import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.os.Handler
import android.os.Looper
import io.github.opencodeandroid.runtime.LocalOpenCode
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/** Native version transition, in a supplied empty document. Upstream source/DOM is never patched.
 * CacheStorage markers live in the WebView profile itself, including isolated test profiles.
 * Cookies, localStorage, IndexedDB, saved drafts and settings are deliberately not cleared.
 */
internal object OfficialResourceCache {
    const val PATH_PREFIX = "/auth/_opencode_android_resources/"
    fun prepare(view: WebView, key: String, ready: () -> Unit, failed: () -> Unit): AutoCloseable {
        require(key.matches(Regex("[A-Za-z0-9-]{1,128}")))
        val original = view.webViewClient
        val marker = JSONObject.quote("opencode-android-resources-$key")
        val nonce = UUID.randomUUID().toString()
        val bootstrap = LocalOpenCode.ORIGIN + PATH_PREFIX + nonce
        val flag = "__opencodeNativeResourceTransition"
        var begun = false; var polls = 0; var clearing = false; var closed = false
        val handler = Handler(Looper.getMainLooper())
        lateinit var temporary: WebViewClient
        lateinit var poll: Runnable
        fun finish(success: Boolean) {
            if (closed || view.webViewClient !== temporary) return
            closed = true; handler.removeCallbacks(poll); view.webViewClient = original
            if (success) ready() else failed()
        }
        poll = Runnable {
            if (!closed && view.webViewClient === temporary) {
                if (++polls > 120) finish(false)
                else view.evaluateJavascript("window.$flag || ''") { status ->
                    if (closed || view.webViewClient !== temporary) return@evaluateJavascript
                    when (status) {
                        "\"done\"" -> finish(true)
                        "\"error\"" -> finish(false)
                        "\"http\"" -> {
                            if (!clearing) {
                                clearing = true; view.clearCache(true)
                                view.evaluateJavascript("Promise.resolve().then(()=>('caches' in window)?caches.open($marker):null).then(()=>window.$flag='done').catch(()=>window.$flag='error');void 0", null)
                            }
                            handler.postDelayed(poll, 75)
                        }
                        else -> handler.postDelayed(poll, 75)
                    }
                }
            }
        }
        temporary = object : WebViewClient() {
            override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.method == "GET" && request.url.toString() == bootstrap) {
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("<!doctype html><meta charset=utf-8><meta name=opencode-native-transition content=$nonce>".toByteArray()))
                }
                return null
            }
            override fun onPageFinished(webView: WebView, url: String?) {
                if (begun || webView !== view || url != bootstrap) return
                begun = true
                view.evaluateJavascript("""
                    window.$flag='waiting';
                    (async()=>{
                      if(document.querySelector('meta[name=opencode-native-transition]')?.content!==${JSONObject.quote(nonce)}){throw new Error('Unexpected maintenance document');}
                      const names=('caches' in window)?await caches.keys():[];
                      if(names.includes($marker)){window.$flag='done';return;}
                      if('serviceWorker' in navigator){await Promise.all((await navigator.serviceWorker.getRegistrations()).map(registration=>registration.unregister()));}
                      if('caches' in window){await Promise.all(names.filter(name=>name.startsWith('workbox-precache-')||name.startsWith('opencode-android-resources-')).map(name=>caches.delete(name)));}
                      window.$flag='http';
                    })().catch(()=>window.$flag='error');void 0
                """.trimIndent().replace(".catch(()=>window.$flag='error')", ".catch(error=>{window.${flag}Error=error.name;window.$flag='error';})"), null)
                handler.post(poll)
            }
            override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                closed = true; handler.removeCallbacks(poll); view.webViewClient = original
                return original.onRenderProcessGone(webView, detail)
            }
        }
        view.webViewClient = temporary
        // Upstream deliberately excludes /auth/ from navigation fallback. Native interception
        // supplies a real HTTP document, required by Chromium's service-worker storage APIs.
        view.loadUrl(bootstrap)
        return AutoCloseable {
            if (!closed) {
                closed = true; handler.removeCallbacks(poll)
                if (view.webViewClient === temporary) view.webViewClient = original
            }
        }
    }
}
