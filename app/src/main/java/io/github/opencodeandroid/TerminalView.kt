package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewAssetLoader
import io.github.opencodeandroid.runtime.PtySession
import io.github.opencodeandroid.runtime.TerminalSessions
import io.github.opencodeandroid.runtime.WorkspaceContext
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalWorkspace(ready: Boolean, modifier: Modifier = Modifier) {
    val strings = rememberNativeStrings()
    val sessions by TerminalSessions.sessions.collectAsStateWithLifecycle()
    val focused by TerminalSessions.focusedSession.collectAsStateWithLifecycle()
    val directory by WorkspaceContext.directory.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var selectNew by remember { mutableStateOf(false) }
    LaunchedEffect(ready) { if (ready && sessions.isEmpty()) TerminalSessions.open() }
    LaunchedEffect(sessions.size) {
        if (selectNew && sessions.isNotEmpty()) { selected = sessions.last().id; selectNew = false }
        else if (sessions.none { it.id == selected }) selected = sessions.firstOrNull()?.id ?: -1
    }
    LaunchedEffect(focused, sessions.size) { if (sessions.any { it.id == focused }) selected = focused!! }
    val shell = sessions.find { it.id == selected }
    val embedded = LocalWorkspacePane.current
    Column(modifier) {
        if (!ready) {
            Text(strings.text(R.string.native_43a5447014fd), Modifier.padding(20.dp))
            return@Column
        }
        if (embedded) Row(Modifier.fillMaxWidth().height(36.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                sessions.forEach { session ->
                    val exit by session.exit.collectAsStateWithLifecycle()
                    TextButton({ selected = session.id; TerminalSessions.focus(session.id) }, Modifier.height(36.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (session.id == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant), contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("Shell ${session.id}" + if (exit != null) " · $exit" else "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            TextButton({ selectNew = true; TerminalSessions.open() }, Modifier.width(40.dp).height(36.dp).semantics { contentDescription = strings.text(R.string.native_8fdcba6ce35a) }, enabled = sessions.size < 8, contentPadding = PaddingValues(0.dp)) { Text("＋") }
            TextButton({ shell?.let { TerminalSessions.close(it.id) } }, Modifier.width(40.dp).height(36.dp).semantics { contentDescription = strings.text(R.string.native_b3b7108a568f) }, enabled = shell != null, contentPadding = PaddingValues(0.dp)) { Text("×") }
        } else FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton({ selectNew = true; TerminalSessions.open() }, enabled = sessions.size < 8) { Text(strings.text(R.string.native_8fdcba6ce35a)) }
            TextButton({ shell?.let { TerminalSessions.close(it.id) } }, enabled = shell != null) { Text(strings.text(R.string.native_b3b7108a568f)) }
        }
        if (!embedded && sessions.isNotEmpty()) {
            ScrollableTabRow(sessions.indexOfFirst { it.id == selected }.coerceAtLeast(0), edgePadding = 8.dp) {
                sessions.forEach { session ->
                    val exit by session.exit.collectAsStateWithLifecycle()
                    Tab(session.id == selected, { selected = session.id; TerminalSessions.focus(session.id) }, text = { Text("Shell ${session.id}" + if (exit != null) " · $exit" else "") })
                }
            }
        }
        if (shell != null) {
            Text(strings.text(R.string.native_0e822affd5da, shell.directory), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            key(shell.id) {
                AndroidView(factory = { context -> TerminalWebView(context, shell) },
                    modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.dispose() })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val keyModifier = if (embedded) Modifier.width(48.dp).height(34.dp) else Modifier
                TextButton({ shell.control = true }, keyModifier) { Text("Ctrl") }
                TextButton({ shell.alt = true }, keyModifier) { Text("Alt") }
                TextButton({ shell.write(byteArrayOf(27)) }, keyModifier) { Text("Esc") }
                TextButton({ shell.write(byteArrayOf(9)) }, keyModifier) { Text("Tab") }
                TextButton({ shell.write(byteArrayOf(3)) }, keyModifier) { Text("^C") }
                TextButton({ shell.write(byteArrayOf(4)) }, keyModifier) { Text("^D") }
            }
        } else Text(strings.text(R.string.native_72d769e3888d, directory), Modifier.padding(20.dp))
    }
}

/** Trusted, APK-only terminal UI. Its bridge exposes PTY input/resize, no Android system API. */
@SuppressLint("SetJavaScriptEnabled")
private class TerminalWebView(context: Context, private val session: PtySession) : WebView(context) {
    private val browserScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private val bridge = TerminalBridge()

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path.orEmpty().startsWith("/assets/terminal/") && request.method == "GET") {
                    return loader.shouldInterceptRequest(uri)
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
        }
        addJavascriptInterface(bridge, "TerminalIO")
        loadUrl("https://appassets.androidplatform.net/assets/terminal/index.html")
    }

    private inner class TerminalBridge {
        @JavascriptInterface fun write(value: String): Boolean {
            if (value.length > 96 * 1024) return false
            return try { session.write(Base64.decode(value, Base64.NO_WRAP)) } catch (_: IllegalArgumentException) { false }
        }
        @JavascriptInterface fun resize(rows: Int, columns: Int) { session.resize(rows, columns) }
        @JavascriptInterface fun ready() {
            post {
                watcher?.cancel()
                val history = session.snapshot()
                var sequence = history.sequence
                evaluateJavascript("window.resetTerminal(\"${Base64.encodeToString(history.bytes, Base64.NO_WRAP)}\")", null)
                watcher = browserScope.launch {
                    session.events.collect { chunk ->
                        if (chunk.sequence > sequence) {
                            evaluateJavascript("window.writeTerminal(\"${Base64.encodeToString(chunk.bytes, Base64.NO_WRAP)}\")", null)
                            sequence = chunk.sequence
                        }
                    }
                }
                session.requestRedraw()
            }
        }
    }

    fun dispose() {
        watcher?.cancel(); browserScope.cancel()
        removeJavascriptInterface("TerminalIO")
        stopLoading(); destroy()
    }
}
