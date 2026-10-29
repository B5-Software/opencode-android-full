package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.opencodeandroid.runtime.LocalOpenCode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.opencodeandroid.runtime.BrowserAuthentication
import io.github.opencodeandroid.runtime.WorkspaceContext
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Alignment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BrowserNavigation {
    internal var view: WebView? = null
    fun goBack(onEmpty: () -> Unit): Boolean {
        val browser = view ?: return false
        if (!localPage(browser.url)) return false
        browser.evaluateJavascript("""(()=>{
            const overlay=Array.from(document.querySelectorAll('[role="dialog"],[role="alertdialog"],[role="menu"],[role="listbox"]')).some(node=>node.getClientRects().length>0&&getComputedStyle(node).visibility!=='hidden');
            if(overlay){for(const type of ['keydown','keyup'])(document.activeElement||document).dispatchEvent(new KeyboardEvent(type,{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true}));}
            return overlay;
        })()""".trimIndent()) { handled ->
            if (view !== browser || handled == "true") return@evaluateJavascript
            val history = browser.copyBackForwardList()
            for (index in history.currentIndex - 1 downTo 0) {
                if (restorablePage(history.getItemAtIndex(index).url)) {
                    browser.goBackOrForward(index - history.currentIndex)
                    return@evaluateJavascript
                }
            }
            onEmpty()
        }
        return true
    }
}

private fun localPage(url: String?): Boolean = url?.let { value ->
    val uri = Uri.parse(value)
    uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == LocalOpenCode.PORT
} == true

private fun restorablePage(url: String?) = localPage(url) && !Uri.parse(url).path.orEmpty().startsWith("/auth/connect/") && !Uri.parse(url).path.orEmpty().startsWith(OfficialResourceCache.PATH_PREFIX)

/** A cold browser restore must use the confirmed persisted workspace rather than
 * an older Activity bundle (including a legacy /root session). Live views stay intact. */
internal fun startupSessionReplaces(restoredUrl: String?, startupUrl: String?): Boolean {
    val selected = WorkspaceContext.localSession(startupUrl) ?: return false
    return WorkspaceContext.localSession(restoredUrl) != selected
}

private fun Context.fileChooserOwner(): WebFileChooserOwner? {
    var candidate = this
    repeat(8) {
        if (candidate is WebFileChooserOwner) return candidate as WebFileChooserOwner
        candidate = (candidate as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficialOpenCodeView(pairingUrl: String, modifier: Modifier = Modifier, navigation: BrowserNavigation? = null,
    requestedUrl: String? = null, onSessionChanged: ((String) -> Unit)? = null, startupUrl: String? = null, onReconnect: (() -> Unit)? = null) {
    val strings = rememberNativeStrings()
    val managedPairing = localPage(pairingUrl) && Uri.parse(pairingUrl).path.orEmpty().startsWith("/auth/connect/")
    val context = androidx.compose.ui.platform.LocalContext.current
    val browserBackground = MaterialTheme.colorScheme.background.toArgb()
    val retained = context.mainActivity()?.retainedBrowser
    val resourceKey = remember(pairingUrl) { if (managedPairing) io.github.opencodeandroid.runtime.update.OpenCodeUpdates(io.github.opencodeandroid.runtime.RuntimePaths(context)).browserKey() else "" }
    val retentionKey = if (managedPairing) "${LocalOpenCode.ORIGIN}:$resourceKey" else pairingUrl
    val savedBrowser = rememberSaveable(retentionKey) { Bundle() }
    var rendererGeneration by remember { mutableIntStateOf(0) }
    var preparingResources by remember(rendererGeneration, retentionKey) { mutableStateOf(false) }
    var resourceFailure by remember(rendererGeneration, retentionKey) { mutableStateOf(false) }
    val resourcePreparation = remember(rendererGeneration, retentionKey) { java.util.concurrent.atomic.AtomicReference<AutoCloseable?>() }
    var currentBrowser by remember { mutableStateOf<WebView?>(null) }
    var authenticated by remember(pairingUrl) { mutableStateOf<Boolean?>(if (managedPairing) null else true) }
    var attempt by remember(pairingUrl) { mutableIntStateOf(0) }
    val reconnect by rememberUpdatedState(onReconnect)
    val sessionChanged by rememberUpdatedState(onSessionChanged)
    val startupDestination by rememberUpdatedState(startupUrl)
    val requestedSession by rememberUpdatedState(WorkspaceContext.localSession(requestedUrl))
    val browserScope = rememberCoroutineScope()
    LaunchedEffect(requestedUrl, currentBrowser, authenticated, preparingResources, resourceFailure) {
        val view = currentBrowser ?: return@LaunchedEffect
        if (authenticated == true && !preparingResources && !resourceFailure && WorkspaceContext.localSession(requestedUrl) != null && view.url != requestedUrl) view.loadUrl(requestedUrl!!)
    }
    LaunchedEffect(pairingUrl, attempt) {
        if (!managedPairing) return@LaunchedEffect
        authenticated = null
        authenticated = try { BrowserAuthentication.connect(pairingUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (authenticated == false) reconnect?.invoke()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) currentBrowser?.let { it.saveState(savedBrowser); it.onPause() }
            if (event == Lifecycle.Event.ON_RESUME) currentBrowser?.onResume()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (authenticated != true) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (authenticated == null) CircularProgressIndicator()
                Text(if (authenticated == null) strings.text(R.string.native_106ee775b538) else strings.text(R.string.native_a021adc1f449))
                if (authenticated == false) TextButton({ reconnect?.invoke() ?: run { attempt++ } }) { Text(strings.text(R.string.native_dacb8837471c)) }
            }
        }
        return
    }
    Box(modifier) {
    key(rendererGeneration, retentionKey) {
    AndroidView(modifier = Modifier.matchParentSize(), factory = { context ->
        val reused = retained?.acquire(retentionKey)
        (reused ?: WebView(context)).apply {
            currentBrowser = this
            navigation?.view = this
            setBackgroundColor(browserBackground)
            // WebView treats WRAP_CONTENT as a content-sized viewport; CSS vh/dvh can
            // become zero even when Compose subsequently measures a visible height.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(false)
            WebViewRendering.view(this)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    if (!localPage(view.url)) { callback.onReceiveValue(null); return true }
                    val owner = context.fileChooserOwner()
                    if (owner == null) { callback.onReceiveValue(null); return true }
                    return owner.webFilePicker.show(view, callback, params)
                }
            }
            // No JavaScript-to-Android system bridge is attached to upstream content.
            var repairing = false
            var clearTransitionHistory = false
            var restoringSession = if (reused == null && managedPairing) WorkspaceContext.localSession(startupDestination) else null
            fun observeSession(url: String?) {
                val id = WorkspaceContext.localSession(url) ?: return
                // restoreState may briefly emit an old /root history entry before
                // the confirmed startup session loads. Do not persist that detour.
                if (restoringSession != null && id != restoringSession && id != requestedSession) return
                restoringSession = null
                sessionChanged?.invoke(id)
            }
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (navigation?.view === view) navigation.view = null
                    if (currentBrowser === view) { currentBrowser = null; rendererGeneration++ }
                    retained?.discard(view, rendererGone = true) ?: view.destroy()
                    return true
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (currentBrowser === view) observeSession(url)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    if (clearTransitionHistory && localPage(url)) { view.clearHistory(); clearTransitionHistory = false }
                    if (currentBrowser === view) observeSession(url)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (!managedPairing || repairing || response.statusCode != 401 || !localPage(request.url.toString())) return
                    if (!request.url.path.orEmpty().startsWith("/api/") && !request.isForMainFrame) return
                    repairing = true
                    browserScope.launch {
                        // A provider's own API can also return 401. Re-pair only if this browser's
                        // authenticated /api/info check fails, preserving the upstream provider login flow.
                        val ready = BrowserAuthentication.isReady()
                        if (currentBrowser !== view) return@launch
                        if (ready) { repairing = false; return@launch }
                        if (restorablePage(view.url)) view.saveState(savedBrowser)
                        authenticated = false
                        reconnect?.invoke()
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == LocalOpenCode.PORT) return false
                    if (request.isForMainFrame && request.hasGesture() && uri.scheme in listOf("https", "http")) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    return true
                }
            }
            if (reused == null) {
                fun restorePage() {
                    preparingResources = false
                    val restored = restoreState(savedBrowser)
                    val destination = startupDestination?.takeIf { WorkspaceContext.localSession(it) != null }
                    if (!restorablePage(restored?.currentItem?.url) || (managedPairing && startupSessionReplaces(restored?.currentItem?.url, destination))) {
                        clearTransitionHistory = managedPairing
                        loadUrl(if (managedPairing) destination ?: LocalOpenCode.ORIGIN else pairingUrl)
                    }
                }
                if (managedPairing) {
                    preparingResources = true
                    resourcePreparation.set(OfficialResourceCache.prepare(this, resourceKey, ready = { if (currentBrowser === this) restorePage() }, failed = { preparingResources = false; resourceFailure = true }))
                } else restorePage()
            } else if (managedPairing && WorkspaceContext.localSession(url) == null) {
                startupDestination?.takeIf { WorkspaceContext.localSession(it) != null }?.let(::loadUrl)
            }
            post { if (currentBrowser === this) WorkspaceContext.localSession(url)?.let { sessionChanged?.invoke(it) } }
        }
    }, update = { view -> view.setBackgroundColor(browserBackground) }, onRelease = release@{ view ->
        resourcePreparation.getAndSet(null)?.close()
        if (retained?.isDisposed(view) == true) return@release
        view.context.fileChooserOwner()?.webFilePicker?.cancel(view)
        if (restorablePage(view.url)) view.saveState(savedBrowser)
        if (navigation?.view === view) navigation.view = null
        if (currentBrowser === view) currentBrowser = null
        if (preparingResources || resourceFailure) retained?.discard(view) ?: run { view.stopLoading(); view.destroy() }
        else if (retained != null) retained.release(retentionKey, view) else { view.stopLoading(); view.destroy() }
    })
    }
    if (preparingResources || resourceFailure) androidx.compose.material3.Surface(Modifier.matchParentSize()) {
        Box(contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (preparingResources) CircularProgressIndicator()
            Text(if (resourceFailure) strings.text(R.string.native_17584aa60ae6) else strings.text(R.string.native_3b3b80064ccb))
            if (resourceFailure) TextButton({ rendererGeneration++ }) { Text(strings.text(R.string.native_2560ee8fdd16)) }
        } }
    }
    }
}
