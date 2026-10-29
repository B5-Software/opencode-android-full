package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.files.ProjectFilesViewModel
import io.github.opencodeandroid.files.ProjectFilesPage
import io.github.opencodeandroid.desktop.*
import io.github.opencodeandroid.bridge.AndroidBridge
import io.github.opencodeandroid.bridge.BridgeRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

class MainActivity : ComponentActivity(), WebFileChooserOwner {
    internal val retainedBrowser = RetainedOfficialBrowser(this)
    private val renderPreferences by lazy { getSharedPreferences(WebViewRendering.PREFERENCES, 0) }
    private val renderPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> WebViewRendering.window(this, window) }
    override val webFilePicker = WebFilePicker(this)
    private val projectFiles by viewModels<ProjectFilesViewModel>()
    private var openCodeRequest by mutableIntStateOf(0)
    internal var workspaceShortcuts: ((WorkspaceCommand) -> Boolean)? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebViewRendering.window(this, window)
        renderPreferences.registerOnSharedPreferenceChangeListener(renderPreferenceListener)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        enterFullscreen()
        android.webkit.WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        RuntimeState.refreshInstalled(RuntimePaths(this).installed)
        WorkspaceContext.initialize(this)
        DevelopmentSessions.refresh(this)
        if (savedInstanceState == null && intent.action == OPEN_WORKSPACE) openCodeRequest = 1
        AndroidBridge.focus(intent.getStringExtra("bridge-request-id"))
        setContent {
            OpenCodeAppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Workspace(onAction = ::runAction, onExit = ::finish, projectFiles = projectFiles, onDesktopAction = ::desktopAction, openCodeRequest = openCodeRequest)
                    BridgeRequests()
                }
            }
        }
        if (openCodeRequest > 0 && RuntimeState.current.value.ready && !RuntimeState.current.value.busy) dispatchAction(RuntimeService.START, automatic = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }
    // This is Activity's public framework callback; AndroidX marks its forwarding override restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val command = WorkspaceCommand.from(event)
        if (command != null && workspaceShortcuts != null) {
            if (event.action == android.view.KeyEvent.ACTION_UP) return true
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount > 0) return true
                if (workspaceShortcuts?.invoke(command) == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onResume() { super.onResume(); AndroidBridge.foreground = true }
    override fun onPause() { AndroidBridge.foreground = false; super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); AndroidBridge.focus(intent.getStringExtra("bridge-request-id"))
        if (intent.action == OPEN_WORKSPACE) {
            openCodeRequest++
            if (RuntimeState.current.value.ready && !RuntimeState.current.value.busy) dispatchAction(RuntimeService.START, automatic = true)
        }
    }

    private fun enterFullscreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun runAction(action: String) = dispatchAction(action, automatic = false)
    private fun dispatchAction(action: String, automatic: Boolean) {
        if (action !in setOf(RuntimeService.STOP, RuntimeService.RECONNECT) && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = Intent(this, RuntimeService::class.java).setAction(action).putExtra(RuntimeService.AUTOMATIC_START, automatic)
        if (action == RuntimeService.STOP) {
            if (DesktopState.current.value.busy) desktopAction(DesktopService.STOP, "ustc", "1280x720")
            startService(intent)
        } else if (action == RuntimeService.RECONNECT) startService(intent)
        else startForegroundService(intent)
    }

    private fun desktopAction(action: String, mirror: String, resolution: String) {
        if (action !in setOf(DesktopService.STOP, DesktopService.OPEN_PROJECT) && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val intent = Intent(this, DesktopService::class.java).setAction(action).putExtra("mirror", mirror).putExtra("resolution", resolution)
        if (action in setOf(DesktopService.STOP, DesktopService.OPEN_PROJECT)) startService(intent) else startForegroundService(intent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) retainedBrowser.trim()
    }
    override fun onDestroy() {
        renderPreferences.unregisterOnSharedPreferenceChangeListener(renderPreferenceListener)
        webFilePicker.cancel(); super.onDestroy(); retainedBrowser.close()
    }
    companion object { const val OPEN_WORKSPACE = "io.github.opencodeandroid.OPEN_WORKSPACE" }
}

@Composable
internal fun Workspace(onAction: (String) -> Unit, onExit: () -> Unit, projectFiles: ProjectFilesViewModel, onDesktopAction: (String, String, String) -> Unit, openCodeRequest: Int = 0) {
    val strings = rememberNativeStrings()
    val state by RuntimeState.status.collectAsStateWithLifecycle()
    val desktop by DesktopState.status.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(if (state.pairingUrl != null || openCodeRequest > 0) 0 else 4) }
    LaunchedEffect(openCodeRequest) { if (openCodeRequest > 0) selected = 0 }
    LaunchedEffect(state.pairingUrl, state.phase) {
        if (selected == 4 && state.pairingUrl != null && state.phase == "OpenCode running") selected = 0
    }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    var stopOnExit by rememberSaveable { mutableStateOf(false) }
    val browser = remember { BrowserNavigation() }
    val pages = rememberSaveableStateHolder()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val files by projectFiles.state.collectAsStateWithLifecycle()
    val directory by WorkspaceContext.directory.collectAsStateWithLifecycle()
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionRequest by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var requestSequence by remember { mutableLongStateOf(0L) }
    fun observeSession(id: String) {
        sessionRequest = id to ++requestSequence
        if (WorkspaceContext.localSession(pendingUrl) == id) pendingUrl = null
    }
    fun select(page: Int) {
        val changed = selected != page
        selected = page
        if (page == 0 && changed) WorkspaceContext.localSession(browser.view?.url)?.let(::observeSession)
    }
    LaunchedEffect(directory, state.pairingUrl) { projectFiles.followProject(directory) }
    LaunchedEffect(sessionRequest, state.pairingUrl) {
        val id = sessionRequest?.first ?: return@LaunchedEffect
        if (state.pairingUrl == null) return@LaunchedEffect
        val generation = WorkspaceContext.generation
        val project = try { withContext(Dispatchers.IO) { LocalOpenCode(CredentialVault(RuntimePaths(context)).password()).sessionDirectory(id) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@LaunchedEffect }
        try {
            val saved = withContext(Dispatchers.IO) { WorkspaceContext.rememberSessionIfCurrent(project, id, generation) }
            if (saved) RuntimeState.workspace(WorkspaceContext.sessionUrl(id))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RuntimeState.log(strings.text(R.string.native_b07809340403)) }
    }
    LaunchedEffect(files.openCodeUrl, files.openTerminal, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            files.openCodeUrl?.let { url -> if (projectFiles.consumeOpenCode(url)) { pendingUrl = url; selected = 0 } }
            if (files.openTerminal && projectFiles.consumeTerminal()) selected = 1
        }
    }
    BackHandler {
        when {
            menuOpen -> menuOpen = false
            selected == 0 && browser.goBack { if (selected == 0) confirmExit = true } -> Unit
            selected == 3 && projectFiles.back() -> Unit
            else -> confirmExit = true
        }
    }
    AdaptiveWorkspace(selected, ::select, state.pairingUrl != null, menuOpen, { menuOpen = it },
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { page ->
        pages.SaveableStateProvider(page) {
            when (page) {
                0 -> if (state.pairingUrl != null) OfficialOpenCodeView(state.pairingUrl!!, Modifier.fillMaxSize(), browser, pendingUrl, ::observeSession, startupUrl = state.workspaceUrl) { onAction(RuntimeService.RECONNECT) }
                     else EmptyWorkspacePage(0, if (state.busy) strings.text(R.string.native_e4ab10392d05) else if (state.phase == "Interrupted") strings.text(R.string.native_20ea15973287) else strings.text(R.string.native_f2f2f6d32544), { selected = 4 })
                1 -> TerminalWorkspace(state.pairingUrl != null, Modifier.fillMaxSize())
                2 -> DesktopPage(onDesktopAction)
                3 -> ProjectFilesPage(projectFiles,
                    onOpenCode = if (state.ready && (!state.busy || state.pairingUrl != null)) ({ relative -> projectFiles.openProject(relative, false) { onAction(RuntimeService.START) } }) else null,
                    onOpenTerminal = if (state.ready && (!state.busy || state.pairingUrl != null)) ({ relative -> projectFiles.openProject(relative, true) { onAction(RuntimeService.START) } }) else null)
                else -> RuntimePage(files.dirty, files.busy, onAction)
            }
        }
    }
    io.github.opencodeandroid.backup.WorkstationBackupDialogs(files.dirty) { projectFiles.usesOldEnvironment() }
    if (confirmExit) {
        AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text(strings.text(R.string.native_abbac5c22111)) },
            text = {
                Column {
                    Text(if (state.busy || desktop.busy) strings.text(R.string.native_fd8eb11aab0a) else strings.text(R.string.native_814a8fb24b62))
                    if (state.busy || desktop.busy) Row(Modifier.padding(top = 12.dp)) {
                        Checkbox(stopOnExit, { stopOnExit = it })
                        Text(strings.text(R.string.native_a76464945c2d), Modifier.padding(top = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton({ if (stopOnExit && (state.busy || desktop.busy)) onAction(RuntimeService.STOP); confirmExit = false; onExit() }) { Text(strings.text(R.string.native_b814fd8d6bc5)) } },
            dismissButton = { TextButton({ confirmExit = false }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimePage(hasUnsavedChanges: Boolean, filesBusy: Boolean, onAction: (String) -> Unit) {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val state by RuntimeState.status.collectAsStateWithLifecycle()
    val desktop by DesktopState.status.collectAsStateWithLifecycle()
    val updateAvailable = remember(state.busy, state.ready) { RootfsInstaller(context, RuntimePaths(context)).updateAvailable() }
    val incomplete = remember(state.busy, state.ready) { !state.ready && RuntimePaths(context).rootfs.exists() }
    Column(Modifier.fillMaxSize().displayCutoutPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Spacer(Modifier.height(12.dp))
        Text(strings.text(R.string.native_6a9b6480b58f), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(if (state.pairingUrl != null) strings.text(R.string.native_b00143b08ff1) else strings.text(R.string.native_51bca456a350), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Text("DEBIAN 13", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Text(when (state.phase) { "Stopped" -> strings.text(R.string.native_75470fb13121); "OpenCode running" -> strings.text(R.string.native_0c2acd0b6057); "Starting", "Starting OpenCode" -> strings.text(R.string.native_cb40c67e7cb1); "Stopping" -> strings.text(R.string.native_44da7f6a451d); "Needs attention" -> strings.text(R.string.native_9a48f8196d8f); else -> strings.message(state.phase) }, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("curl / wget · Git / gh / SSH\nC / C++ · Node / npm · Python · Rust", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!state.ready) Button({ onAction(if (incomplete) RuntimeService.REPAIR else RuntimeService.INSTALL) }, enabled = !state.busy) { Text(if (incomplete) strings.text(R.string.native_b7e1503a7239) else strings.text(R.string.native_7c91bd67ff14)) }
                    else Button({ onAction(if (state.busy) RuntimeService.STOP else RuntimeService.START) }) { Text(if (state.busy) strings.text(R.string.native_cfd73bb04860) else strings.text(R.string.native_e7ec95694dce)) }
                    if (state.ready) OutlinedButton({ onAction(RuntimeService.VERIFY) }, enabled = !state.busy) { Text(strings.text(R.string.native_5d4600a269e9)) }
                }
                if (updateAvailable) {
                    TextButton({ onAction(RuntimeService.UPDATE) }, enabled = !state.busy && !desktop.busy) { Text(strings.text(R.string.native_67b4d4b8d1c0)) }
                    Text(strings.text(R.string.native_c114ec62712b), style = MaterialTheme.typography.bodySmall)
                }
                if (state.ready) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton({ onAction(RuntimeService.INSTALL_LANGUAGE_SERVERS) }, enabled = !state.busy && !desktop.busy) { Text(strings.text(R.string.native_eb3114155716)) }
                    Text(strings.text(R.string.native_01af25e5a7d9), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (desktop.busy) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton({ context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP)) }) { Text(strings.text(R.string.native_7b0455a8daa4)) }
        }
        Spacer(Modifier.height(20.dp))
        io.github.opencodeandroid.preview.PreviewLauncher()
        WebViewRenderingControls()
        Spacer(Modifier.height(16.dp))
        OpenCodeUpdateControls()
        Spacer(Modifier.height(16.dp))
        io.github.opencodeandroid.backup.WorkstationBackupControls(hasUnsavedChanges, filesBusy) { onAction(RuntimeService.RECOVER) }
        Spacer(Modifier.height(16.dp))
        DevelopmentRecoveryControls(onAction)
        Spacer(Modifier.height(16.dp))
        RuntimeDiagnosticRecord()
        Spacer(Modifier.height(16.dp))
        Text(strings.text(R.string.native_2c7251cce661), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun RuntimeDiagnosticRecord() {
    val strings = rememberNativeStrings()
    val state by RuntimeState.current.collectAsStateWithLifecycle()
    Text(strings.text(R.string.native_d1fa7ef127a7), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(10.dp))
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SelectionContainer { Text(state.log.ifEmpty { strings.text(R.string.native_dcef145e6a9b) }, Modifier.fillMaxWidth().padding(16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EmptyWorkspacePage(page: Int, description: String, openRuntime: () -> Unit) {
    val strings = rememberNativeStrings()
    Column(Modifier.fillMaxSize().displayCutoutPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        WorkspaceGlyph(page, MaterialTheme.colorScheme.primary, Modifier.size(36.dp))
        Spacer(Modifier.height(18.dp))
        Text(strings.message(WorkspaceDestinations[page].label), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(strings.message(description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        TextButton(openRuntime) { Text(strings.text(R.string.native_a8243a4e0d22)) }
    }
}
