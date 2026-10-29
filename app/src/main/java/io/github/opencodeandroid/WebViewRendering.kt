package io.github.opencodeandroid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.WeakHashMap

/** Native scheduling only. Web content and upstream JavaScript/CSS are not rewritten. */
internal object WebViewRendering {
    const val PREFERENCES = "webview-rendering"
    fun balanced(context: Context) = context.getSharedPreferences(PREFERENCES, 0).getBoolean("balanced", true)
    fun setBalanced(context: Context, value: Boolean) { context.getSharedPreferences(PREFERENCES, 0).edit().putBoolean("balanced", value).apply() }
    fun window(context: Context, window: Window, balanced: Boolean = balanced(context)) {
        window.attributes = window.attributes.apply { preferredRefreshRate = if (balanced) 60f else 0f }
        if (Build.VERSION.SDK_INT >= 35) window.decorView.requestedFrameRate = if (balanced) 60f else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        if (Build.VERSION.SDK_INT >= 36) window.setFrameRateBoostOnTouchEnabled(!balanced)
    }
    fun view(view: WebView, balanced: Boolean = balanced(view.context)) {
        // A hardware-accelerated window renders this directly; an extra forced hardware layer
        // would add a viewport-sized texture. Keep the normal compositor path.
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.settings.offscreenPreRaster = false
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        if (Build.VERSION.SDK_INT >= 35) view.requestedFrameRate = if (balanced) 60f else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    }
}

internal fun Context.mainActivity(): MainActivity? {
    var context = this
    repeat(8) {
        if (context is MainActivity) return context as MainActivity
        context = (context as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

/** At most one detached official page per Activity, released under memory pressure or destruction. */
internal class RetainedOfficialBrowser(private val activity: Activity) {
    private var idle: Pair<String, WebView>? = null
    private val disposed = WeakHashMap<WebView, Boolean>()
    private var closed = false
    fun isDisposed(view: WebView) = disposed.containsKey(view)
    fun acquire(key: String): WebView? {
        val cached = idle ?: return null
        idle = null
        if (closed || cached.first != key) { discard(cached.second); return null }
        return cached.second.apply { visibility = View.VISIBLE; onResume(); WebViewRendering.view(this) }
    }
    fun release(key: String, view: WebView) {
        if (isDisposed(view)) return
        if (closed || activity.isFinishing || activity.isDestroyed) { discard(view); return }
        idle?.second?.takeIf { it !== view }?.let(::discard)
        view.clearFocus()
        view.onPause(); view.visibility = View.GONE
        view.webChromeClient = null
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { discard(view, rendererGone = true); return true }
        }
        (view.parent as? ViewGroup)?.removeView(view)
        idle = key to view
    }
    fun discard(view: WebView, rendererGone: Boolean = false) {
        if (disposed.put(view, true) != null) return
        if (idle?.second === view) idle = null
        (view.parent as? ViewGroup)?.removeView(view)
        if (!rendererGone) view.stopLoading()
        view.destroy()
    }
    fun trim() { idle?.second?.let(::discard); idle = null }
    fun close() { closed = true; trim() }
}
