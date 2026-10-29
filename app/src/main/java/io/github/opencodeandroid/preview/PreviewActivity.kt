package io.github.opencodeandroid.preview

import io.github.opencodeandroid.i18n.nativeMessage
import io.github.opencodeandroid.i18n.rememberNativeStrings
import io.github.opencodeandroid.stableImeViewport

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.opencodeandroid.BuildConfig
import io.github.opencodeandroid.WebFilePicker
import io.github.opencodeandroid.OpenCodeAppTheme
import io.github.opencodeandroid.WebViewRendering
import io.github.opencodeandroid.R
import java.io.ByteArrayInputStream

private object PreviewProfile {
    private var configured = false
    @Synchronized fun configure() {
        val process = Application.getProcessName()
        check(process.endsWith(":preview") || (BuildConfig.DEBUG && process.endsWith(":preview_validation")))
        if (configured) return
        // Before EVERY android.webkit call in this process, including CookieManager.
        WebView.setDataDirectorySuffix(if (process.endsWith(":preview_validation")) "project-preview-validation" else "project-preview")
        configured = true
    }
}

/** Own process/profile: no native pairing links, Basic auth, core cookies or JS system bridge. */
open class PreviewActivity : ComponentActivity() {
    private val filePicker = WebFilePicker(this)
    private var browser: WebView? = null
    private var failure by mutableStateOf<String?>(null)
    private var probePageSent = false
    private val balancedRendering get() = intent.getBooleanExtra(RENDERING, WebViewRendering.balanced(this))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = runCatching { PreviewTarget.forPort(intent.getIntExtra(PORT, -1)) }.getOrNull()
        if (target == null) { finish(); return }
        try { PreviewProfile.configure() } catch (_: IllegalStateException) {
            Toast.makeText(this, nativeMessage("无法隔离预览浏览数据，请重新打开预览"), Toast.LENGTH_LONG).show()
            finish(); return
        }
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        WebViewRendering.window(this, window, balancedRendering)
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val view = WebView(this).also {
            it.layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            it.setBackgroundColor(getColor(R.color.workspace_background))
            WebViewRendering.view(it, balancedRendering)
            browser = it
        }
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            setGeolocationEnabled(false); mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.allowFileAccess = false
            serviceWorkerWebSettings.allowContentAccess = false
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    if (PreviewTarget.resourceAllowed(request.url.toString())) null else blocked()
            })
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowFileChooser(webView: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!target.ownsNavigation(webView.url.orEmpty())) { callback.onReceiveValue(null); return true }
                return filePicker.show(webView, callback, params)
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (target.ownsNavigation(url)) return false
                if (request.isForMainFrame && request.hasGesture() && request.url.scheme == "https" && PreviewTarget.resourceAllowed(url))
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                return true
            }
            override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (PreviewTarget.resourceAllowed(request.url.toString())) null else blocked()
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) { handler.cancel() }
            override fun onReceivedError(webView: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) failure = "无法加载项目页面，请检查终端中的开发服务器。"
            }
            override fun onPageFinished(webView: WebView, url: String) {
                if (target.ownsNavigation(url) && !probePageSent) { probePageSent = true; sendProbe("page", target) }
            }
            override fun onRenderProcessGone(webView: WebView, detail: RenderProcessGoneDetail): Boolean {
                browser = null; webView.destroy(); finish(); return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val history = view.copyBackForwardList()
                for (index in history.currentIndex - 1 downTo 0) if (target.ownsNavigation(history.getItemAtIndex(index).url)) {
                    view.goBackOrForward(index - history.currentIndex); return
                }
                finish()
            }
        })
        setContent {
            OpenCodeAppTheme {
                val strings = rememberNativeStrings()
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().displayCutoutPadding().stableImeViewport()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ finish() }) { Text(strings.message("返回")) }
                            Text(target.url.removePrefix("http://").removeSuffix("/"), Modifier.padding(top = 15.dp), style = MaterialTheme.typography.labelMedium)
                            TextButton({ failure = null; view.reload() }) { Text(strings.message("刷新")) }
                        }
                        if (failure != null) Text(strings.message(failure!!), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                        AndroidView({ view }, Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
        fun load() {
            sendProbe("native", target)
            val history = savedInstanceState?.getBundle("preview-browser")?.let(view::restoreState)
            if (history == null || !target.ownsNavigation(history.currentItem?.url.orEmpty())) view.loadUrl(target.url)
        }
        val seed = if (validationProcess()) intent.getStringExtra("preview-probe-seed")?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,100}")) } else null
        if (seed == null) load() else CookieManager.getInstance().setCookie(target.url, "$seed=preview-fixture; Path=/; HttpOnly; SameSite=Lax") { load() }
    }

    private fun validationProcess() = BuildConfig.DEBUG && Application.getProcessName().endsWith(":preview_validation")
    @Suppress("DEPRECATION")
    private fun sendProbe(phase: String, target: PreviewTarget) {
        if (!validationProcess()) return
        val receiver = intent.getParcelableExtra<ResultReceiver>("preview-probe-receiver") ?: return
        val name = intent.getStringExtra("preview-probe-cookie")?.takeIf { it.matches(Regex("[a-zA-Z0-9_]{1,100}")) } ?: return
        val cookies = CookieManager.getInstance().getCookie(target.url).orEmpty()
        receiver.send(0, Bundle().apply {
            putString("phase", phase); putInt("pid", Process.myPid())
            putBoolean("hasMainCookie", cookies.split(';').any { it.trim().startsWith("$name=") })
        })
        if (phase == "page") Handler(Looper.getMainLooper()).post { finish() }
    }
    override fun onResume() { super.onResume(); WebViewRendering.window(this, window, balancedRendering); browser?.onResume() }
    override fun onPause() { browser?.onPause(); super.onPause() }
    override fun onDestroy() {
        filePicker.cancel()
        browser?.apply { (parent as? android.view.ViewGroup)?.removeView(this); stopLoading(); destroy() }
        browser = null
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        browser?.let { view -> Bundle().also { view.saveState(it); outState.putBundle("preview-browser", it) } }
        super.onSaveInstanceState(outState)
    }
    companion object {
        private const val PORT = "preview-port"
        private const val RENDERING = "preview-balanced-rendering"
        // Explicit policy avoids stale SharedPreferences cached by this separate process.
        fun intent(context: Context, port: Int) = Intent(context, PreviewActivity::class.java).putExtra(PORT, PreviewTarget.forPort(port).port).putExtra(RENDERING, WebViewRendering.balanced(context))
        private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream("Local service is unavailable in preview".toByteArray()))
    }
}
