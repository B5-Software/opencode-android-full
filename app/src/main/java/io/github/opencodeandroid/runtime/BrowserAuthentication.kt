package io.github.opencodeandroid.runtime

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Cookie-only browser authentication; no server password is ever exposed to JavaScript. */
object BrowserAuthentication {
    suspend fun isReady(): Boolean {
        val cookies = CookieManager.getInstance().getCookie(LocalOpenCode.ORIGIN).orEmpty()
        return withContext(Dispatchers.IO) { LocalOpenCode.browserReady(cookies) }
    }

    suspend fun connect(pairingUrl: String): Boolean {
        val manager = CookieManager.getInstance()
        if (isReady()) return true
        val fresh = withContext(Dispatchers.IO) { LocalOpenCode.browserCookie(pairingUrl) } ?: return false
        val stored = suspendCancellableCoroutine { continuation ->
            manager.setCookie(LocalOpenCode.ORIGIN, fresh) { accepted ->
                if (continuation.isActive) continuation.resume(accepted)
            }
        }
        if (!stored) return false
        manager.flush()
        val renewed = manager.getCookie(LocalOpenCode.ORIGIN).orEmpty()
        return withContext(Dispatchers.IO) { LocalOpenCode.browserReady(renewed) }
    }
}
