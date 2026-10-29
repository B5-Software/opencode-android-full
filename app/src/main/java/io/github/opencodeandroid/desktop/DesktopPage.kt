package io.github.opencodeandroid.desktop

import io.github.opencodeandroid.i18n.rememberNativeStrings
import io.github.opencodeandroid.R

import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.opencodeandroid.runtime.CredentialVault
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.RuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopPage(onAction: (String, String, String) -> Unit) {
    val strings = rememberNativeStrings()
    val state by DesktopState.status.collectAsStateWithLifecycle()
    val runtime by RuntimeState.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mirror by rememberSaveable { mutableStateOf("ustc") }
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    var resolution by rememberSaveable { mutableStateOf(if (portrait) "720x1280" else "1280x720") }
    var browserOptions by rememberSaveable { mutableStateOf(false) }
    val compatibilityFile = remember(context) { java.io.File(RuntimePaths(context).desktop, "chromium-compat-enabled") }
    var compatible by remember { mutableStateOf(compatibilityFile.isFile) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DesktopState.refresh(RuntimePaths(context)) } }
    if (browserOptions) AlertDialog(onDismissRequest = { browserOptions = false }, title = { Text(strings.text(R.string.native_efdb26088f68)) }, text = {
        Text(if (compatible) strings.text(R.string.native_4767f37900f6) else strings.text(R.string.native_a4317f2699a2))
    }, confirmButton = { TextButton({
        if (compatible) compatibilityFile.delete() else { compatibilityFile.parentFile!!.mkdirs(); compatibilityFile.writeText("enabled"); android.system.Os.chmod(compatibilityFile.path, 384) }
        compatible = !compatible; browserOptions = false
    }) { Text(if (compatible) strings.text(R.string.native_3a2596fa9517) else strings.text(R.string.native_eeb5e46d62ae)) } }, dismissButton = { TextButton({ browserOptions = false }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
    if (state.connected) {
        DesktopBrowser(Modifier.fillMaxSize(), { onAction(DesktopService.OPEN_PROJECT, mirror, resolution) }) { browserOptions = true }
        return
    }
    Column(Modifier.fillMaxSize().displayCutoutPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(14.dp))
        Text(strings.text(R.string.native_9a45442649f5), style = MaterialTheme.typography.headlineMedium)
        Text(strings.text(R.string.native_db276240d492), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text(strings.message(state.phase), style = MaterialTheme.typography.titleLarge)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        if (!state.installed) {
            Text(strings.text(R.string.native_fb7f60a63fac), Modifier.padding(vertical = 14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(mirror == "ustc", { mirror = "ustc" }, label = { Text(strings.text(R.string.native_d8eb56c08cd5)) }, enabled = !state.busy)
                FilterChip(mirror == "official", { mirror = "official" }, label = { Text(strings.text(R.string.native_41e865941e16)) }, enabled = !state.busy)
            }
        } else {
            Text(strings.text(R.string.native_ea341b8a102b), Modifier.padding(top = 16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("720x1280", "1024x768", "1280x720", "1600x900").forEach { value -> FilterChip(resolution == value, { resolution = value }, label = { Text(value.replace('x', '×')) }, enabled = !state.busy) }
            }
            Text(strings.text(R.string.native_ff1cde9ba35d), Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button({ onAction(if (state.busy) DesktopService.STOP else if (state.installed) DesktopService.START else DesktopService.INSTALL, mirror, resolution) }, enabled = runtime.ready) {
            Text(if (state.busy) strings.text(R.string.native_aadf816e2dc8) else if (state.installed) strings.text(R.string.native_ba6d7f6c69ac) else strings.text(R.string.native_ea27a25e5114))
        }
        if (state.installed) TextButton({ browserOptions = true }) { Text(if (compatible) strings.text(R.string.native_d3034f064ed3) else strings.text(R.string.native_d708b8c308e2)) }
        if (!runtime.ready) Text(strings.text(R.string.native_0cffb5b0c8e8), Modifier.padding(top = 12.dp))
        DesktopDiagnosticRecord()
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun DesktopDiagnosticRecord() {
    val strings = rememberNativeStrings()
    val state by DesktopState.current.collectAsStateWithLifecycle()
    if (state.log.isNotEmpty()) {
        Spacer(Modifier.height(20.dp)); Text(strings.text(R.string.native_d1fa7ef127a7), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(state.log, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall) }
    }
}

private fun trusted(uri: Uri) = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 6080

@Composable
private fun DesktopBrowser(modifier: Modifier, onProjectFiles: () -> Unit, onBrowserOptions: () -> Unit) {
    val context = LocalContext.current
    var password by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { password = withContext(Dispatchers.IO) { CredentialVault(RuntimePaths(context), "desktop").password() } }
    val secret = password ?: return
    AndroidView(modifier = modifier, factory = { host -> WebView(host).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
        setBackgroundColor(0xff111512.toInt())
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.allowContentAccess = false; settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (trusted(request.url) && request.url.path == "/__chromium_settings__" && request.isForMainFrame && request.hasGesture()) { onBrowserOptions(); return true }
                if (trusted(request.url) && request.url.path == "/__project_files__" && request.isForMainFrame && request.hasGesture()) { onProjectFiles(); return true }
                return !trusted(request.url)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (trusted(request.url)) null else WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                if (host == "127.0.0.1" && realm == "Debian Desktop" && trusted(Uri.parse(view.url.orEmpty()))) handler.proceed("desktop", secret) else handler.cancel()
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (trusted(Uri.parse(url)) && Uri.parse(url).path in listOf("/", "/index.html")) view.evaluateJavascript("window.connectDesktop && window.connectDesktop(${JSONObject.quote(secret.take(8))},${JSONObject.quote(DesktopState.socketToken(secret))})", null)
            }
        }
        loadUrl("http://127.0.0.1:6080/")
    } }, onRelease = { it.stopLoading(); it.destroy() })
}
