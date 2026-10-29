package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal enum class WorkspaceMode(val title: String) { Develop("开发工作区"), Hybrid("OpenCode + 桌面"), Single("单页专注") }

internal data class WorkspaceProfile(
    val files: Boolean, val terminal: Boolean = true, val terminalRight: Boolean = false,
    val filesFraction: Float = .24f, val codeFraction: Float = .5f, val terminalFraction: Float = .32f,
)

internal val LocalWorkspacePane = staticCompositionLocalOf { false }

/** Size classes describe this window, including freeform windows and external displays. */
internal fun workspaceSizeKey(width: Float, height: Float) =
    (if (width < 840) "compact" else if (width < 1200) "expanded" else "large") + if (height < 600) "-short" else "-tall"

internal class WorkspaceLayoutStore(private val preferences: SharedPreferences) {
    fun mode(size: String) = runCatching { WorkspaceMode.valueOf(preferences.getString("$size.mode", null).orEmpty()) }.getOrDefault(WorkspaceMode.Develop)
    fun mode(size: String, mode: WorkspaceMode) { preferences.edit().putString("$size.mode", mode.name).apply() }
    fun profile(size: String, mode: WorkspaceMode): WorkspaceProfile {
        val key = "$size.${mode.name}."
        fun flag(name: String, default: Boolean) = runCatching { preferences.getBoolean(key + name, default) }.getOrDefault(default)
        fun ratio(name: String, default: Float) = runCatching { preferences.getFloat(key + name, default) }.getOrDefault(default).let { if (it.isFinite()) it.coerceIn(.1f, .9f) else default }
        return WorkspaceProfile(flag("files", mode != WorkspaceMode.Hybrid), flag("terminal", true), flag("right", false), ratio("files-ratio", .24f), ratio("code-ratio", .5f), ratio("terminal-ratio", .32f))
    }
    fun profile(size: String, mode: WorkspaceMode, value: WorkspaceProfile) {
        val key = "$size.${mode.name}."
        preferences.edit().putBoolean(key + "files", value.files).putBoolean(key + "terminal", value.terminal).putBoolean(key + "right", value.terminalRight)
            .putFloat(key + "files-ratio", value.filesFraction).putFloat(key + "code-ratio", value.codeFraction).putFloat(key + "terminal-ratio", value.terminalFraction).apply()
    }
}

internal enum class WorkspaceCommand { Code, Terminal, Desktop, Files, Runtime, ToggleFiles, ToggleTerminal, ToggleHybrid, Maximize, Layout;
    companion object {
        fun from(event: AndroidKeyEvent): WorkspaceCommand? {
            // Right Alt can mean AltGr. Leave international text input untouched.
            if (!event.isCtrlPressed || !event.isAltPressed || event.isShiftPressed || event.isMetaPressed || event.metaState and AndroidKeyEvent.META_ALT_RIGHT_ON != 0) return null
            return when (event.keyCode) {
                AndroidKeyEvent.KEYCODE_1 -> Code; AndroidKeyEvent.KEYCODE_2 -> Terminal; AndroidKeyEvent.KEYCODE_3 -> Desktop
                AndroidKeyEvent.KEYCODE_4 -> Files; AndroidKeyEvent.KEYCODE_5 -> Runtime
                AndroidKeyEvent.KEYCODE_B -> ToggleFiles; AndroidKeyEvent.KEYCODE_T -> ToggleTerminal
                AndroidKeyEvent.KEYCODE_M -> ToggleHybrid; AndroidKeyEvent.KEYCODE_ENTER -> Maximize
                AndroidKeyEvent.KEYCODE_BACKSLASH -> Layout; else -> null
            }
        }
    }
}

/** Visible panes keep the same parent and key; changing a split only changes their rectangles. */
@Composable
internal fun AdaptiveWorkspace(
    selected: Int, onSelect: (Int) -> Unit, running: Boolean, menuOpen: Boolean, onMenuOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier, layoutStore: WorkspaceLayoutStore? = null, content: @Composable (Int) -> Unit,
) {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val defaultStore = remember { WorkspaceLayoutStore(context.getSharedPreferences("workspace-layout", Context.MODE_PRIVATE)) }
    val store = layoutStore ?: defaultStore
    var options by remember { mutableStateOf(false) }
    var maximized by rememberSaveable { mutableStateOf(false) }
    var lastMain by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(selected) { if (selected == 0 || selected == 2) lastMain = selected }
    val main = if (selected == 0 || selected == 2) selected else lastMain
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Read the class before IME padding: opening the keyboard must not select a new saved layout.
        val size = workspaceSizeKey(maxWidth.value, maxHeight.value)
        val wide = maxWidth >= 840.dp && maxHeight >= 320.dp
        var mode by remember(size) { mutableStateOf(store.mode(size)) }
        var profile by remember(size, mode) { mutableStateOf(store.profile(size, mode)) }
        fun update(value: WorkspaceProfile) {
            if ((profile.files && !value.files && selected == 3) || (profile.terminal && !value.terminal && selected == 1)) onSelect(main)
            profile = value; store.profile(size, mode, value)
        }
        fun changeMode(value: WorkspaceMode) {
            mode = value; store.mode(size, value); maximized = false
            if (value != WorkspaceMode.Single) onSelect(0)
        }
        val commandHandler by rememberUpdatedState<(WorkspaceCommand) -> Boolean> { command ->
            when (command) {
                WorkspaceCommand.Code, WorkspaceCommand.Terminal, WorkspaceCommand.Desktop, WorkspaceCommand.Files, WorkspaceCommand.Runtime -> onSelect(command.ordinal)
                WorkspaceCommand.ToggleFiles -> update(profile.copy(files = !profile.files))
                WorkspaceCommand.ToggleTerminal -> update(profile.copy(terminal = !profile.terminal))
                WorkspaceCommand.ToggleHybrid -> changeMode(if (mode == WorkspaceMode.Hybrid) WorkspaceMode.Develop else WorkspaceMode.Hybrid)
                WorkspaceCommand.Maximize -> maximized = !maximized
                WorkspaceCommand.Layout -> options = !options
            }
            true
        }
        val activity = context as? MainActivity
        DisposableEffect(activity) {
            val owned: (WorkspaceCommand) -> Boolean = { commandHandler(it) }
            activity?.workspaceShortcuts = owned
            onDispose { if (activity?.workspaceShortcuts === owned) activity.workspaceShortcuts = null }
        }
        BoxWithConstraints(Modifier.fillMaxSize().stableImeViewport().then(if (wide) Modifier.displayCutoutPadding() else Modifier)) {
            val arrangement = arrangeWorkspace(maxWidth.value, maxHeight.value, selected, main, mode, profile, wide && !maximized)
            Box(Modifier.fillMaxSize().testTag("workspace-${arrangement.kind}")) {
                // A flat tree avoids moving AndroidViews between different subcompositions.
                (0..4).forEach { page ->
                    val rect = arrangement.panes[page]
                    if (rect != null) key(page) {
                        Box(Modifier.absoluteOffset(rect.x.dp, rect.y.dp).requiredSize(rect.width.dp, rect.height.dp)) {
                            WorkspacePane(page, arrangement.kind != "single", selected == page, { onSelect(page) }, { onSelect(page); maximized = true }) { content(page) }
                        }
                    }
                }
                arrangement.dividers.forEach { divider -> key(divider.name) {
                    val rect = divider.rect
                    WorkspaceDivider(divider, Modifier.absoluteOffset(rect.x.dp, rect.y.dp).requiredSize(rect.width.dp, rect.height.dp)) { value ->
                        update(when (divider.name) {
                            "files" -> profile.copy(filesFraction = value)
                            "terminal" -> profile.copy(terminalFraction = 1f - value)
                            else -> profile.copy(codeFraction = value)
                        })
                    }
                } }
            }
            Box(Modifier.fillMaxSize().displayCutoutPadding()) {
                FloatingWorkspaceNavigation(selected, running, menuOpen, onMenuOpen, onWorkspaceOptions = {
                    onMenuOpen(false)
                    if (maximized) maximized = false else options = true
                }, workspaceOptionsLabel = if (maximized) strings.text(R.string.native_651ae6ea52ae) else strings.text(R.string.native_d7e97cfac66f), onSelect = onSelect)
            }
        }
        if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(strings.text(R.string.native_d7e97cfac66f)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkspaceMode.entries.forEach { choice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == choice, { changeMode(choice) }, Modifier.testTag("workspace-mode-${choice.name}"))
                        Text(strings.message(choice.title))
                    }
                }
                if (!wide) Text(strings.text(R.string.native_2a03e2ed30f9), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(profile.files, { update(profile.copy(files = it)) }); Text(strings.text(R.string.native_e792dd4cfa78)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(profile.terminal, { update(profile.copy(terminal = it)) }); Text(strings.text(R.string.native_712d956931d1)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!profile.terminalRight, { update(profile.copy(terminalRight = false)) }, label = { Text(strings.text(R.string.native_073cc20d9ed9)) })
                    FilterChip(profile.terminalRight, { update(profile.copy(terminalRight = true)) }, label = { Text(strings.text(R.string.native_5de225480fa7)) })
                }
                Text(strings.text(R.string.native_35cdb3eacdc6), style = MaterialTheme.typography.bodySmall)
                Text(strings.text(R.string.native_babbb870cf97), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ options = false }) { Text(strings.text(R.string.native_7a129a94c6ff)) } }, dismissButton = {
            TextButton({ update(profile.copy(filesFraction = .24f, codeFraction = .5f, terminalFraction = .32f)) }) { Text(strings.text(R.string.native_bcec2218fc21)) }
        })
    }
}

@Composable
private fun WorkspacePane(page: Int, framed: Boolean, focused: Boolean, onFocus: () -> Unit, onMaximize: () -> Unit, content: @Composable () -> Unit) {
    val strings = rememberNativeStrings()
    val currentFocus by rememberUpdatedState(onFocus)
    Column(Modifier.fillMaxSize().testTag("workspace-pane-$page")
        .border(if (framed) 1.dp else 0.dp, if (!framed) Color.Transparent else if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
        .pointerInput(Unit) {
            awaitPointerEventScope { while (true) { if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDownIgnoreConsumed() }) currentFocus() } }
        }) {
        if (framed) Row(Modifier.fillMaxWidth().height(30.dp).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceGlyph(page, MaterialTheme.colorScheme.primary, Modifier.size(15.dp))
            Text(strings.message(WorkspaceDestinations[page].label), Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelSmall)
            TextButton(onMaximize, Modifier.size(30.dp).semantics { contentDescription = strings.text(R.string.native_da88c62d1217, strings.message(WorkspaceDestinations[page].label)) }, contentPadding = PaddingValues(0.dp)) { Text("⤢") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { CompositionLocalProvider(LocalWorkspacePane provides framed) { content() } }
    }
}

internal data class WorkspaceRect(val x: Float, val y: Float, val width: Float, val height: Float)
internal data class WorkspaceDividerGeometry(val name: String, val vertical: Boolean, val rect: WorkspaceRect, val usable: Float, val fraction: Float, val range: ClosedFloatingPointRange<Float>)
internal data class WorkspaceArrangement(val kind: String, val panes: Map<Int, WorkspaceRect>, val dividers: List<WorkspaceDividerGeometry>)

/** Geometry is independent of composition so a pane never needs a different parent layout. */
internal fun arrangeWorkspace(width: Float, height: Float, selected: Int, main: Int, mode: WorkspaceMode, profile: WorkspaceProfile, expanded: Boolean): WorkspaceArrangement {
    val root = WorkspaceRect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    val files = profile.files && (mode != WorkspaceMode.Hybrid || width >= 1120f)
    val right = profile.terminalRight && width >= 1280f
    val terminal = profile.terminal && if (right) height >= 320f else height >= 560f
    if (!expanded || height < 240f || mode == WorkspaceMode.Single || selected == 4 || (selected == 3 && !files) || (selected == 1 && !terminal))
        return WorkspaceArrangement("single", mapOf(selected to root), emptyList())
    val panes = linkedMapOf<Int, WorkspaceRect>()
    val dividers = mutableListOf<WorkspaceDividerGeometry>()
    fun split(rect: WorkspaceRect, name: String, vertical: Boolean, fraction: Float, minimumFirst: Float, minimumSecond: Float): Pair<WorkspaceRect, WorkspaceRect> {
        val gap = minOf(14f, if (vertical) rect.height else rect.width)
        val usable = ((if (vertical) rect.height else rect.width) - gap).coerceAtLeast(0f)
        val fits = usable >= minimumFirst + minimumSecond
        val lower = if (fits) minimumFirst else usable * minimumFirst / (minimumFirst + minimumSecond)
        val upper = if (fits) usable - minimumSecond else lower
        val clamped = ((if (fraction.isFinite()) fraction else .5f) * usable).coerceIn(lower, upper)
        val actual = if (usable > 0) clamped / usable else .5f
        val range = if (usable > 0) (lower / usable)..(upper / usable) else 0f..1f
        val divider = if (vertical) WorkspaceRect(rect.x, rect.y + clamped, rect.width, gap) else WorkspaceRect(rect.x + clamped, rect.y, gap, rect.height)
        dividers += WorkspaceDividerGeometry(name, vertical, divider, usable, actual, range)
        return if (vertical) WorkspaceRect(rect.x, rect.y, rect.width, clamped) to WorkspaceRect(rect.x, rect.y + clamped + gap, rect.width, usable - clamped)
        else WorkspaceRect(rect.x, rect.y, clamped, rect.height) to WorkspaceRect(rect.x + clamped + gap, rect.y, usable - clamped, rect.height)
    }
    var remaining = root
    if (terminal) {
        val mainMinimum = if (files && mode == WorkspaceMode.Hybrid) 848f else 720f
        val (a, b) = split(remaining, "terminal", !right, 1f - profile.terminalFraction, if (right) mainMinimum else 240f, if (right) 280f else 200f)
        remaining = a; panes[1] = b
    }
    if (files) {
        val (a, b) = split(remaining, "files", false, profile.filesFraction, 220f, if (mode == WorkspaceMode.Hybrid) 620f else 420f)
        panes[3] = a; remaining = b
    }
    if (mode == WorkspaceMode.Hybrid) {
        val (a, b) = split(remaining, "code-desktop", false, profile.codeFraction, 300f, 300f)
        panes[0] = a; panes[2] = b
    } else panes[main] = remaining
    return WorkspaceArrangement(if (mode == WorkspaceMode.Hybrid) "hybrid" else "expanded", panes, dividers)
}

/** Pointer, keyboard and accessibility changes share the same bounded ratio. */
@Composable
private fun WorkspaceDivider(geometry: WorkspaceDividerGeometry, modifier: Modifier, onFraction: (Float) -> Unit) {
    val strings = rememberNativeStrings()
    val (name, vertical, _, usable, actual, range) = geometry
    val latestFraction by rememberUpdatedState(actual)
    val latestUpdate by rememberUpdatedState(onFraction)
    val density = LocalDensity.current.density
    val context = LocalContext.current
    fun change(value: Float) { latestUpdate(value.coerceIn(range)) }
    val label = when (name) { "files" -> strings.text(R.string.native_1b4b3afbe52d); "terminal" -> strings.text(R.string.native_707c0891a0e6); else -> strings.text(R.string.native_ea8048b59238) }
    Box(modifier.testTag("workspace-divider-$name").semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(actual, range)
        setProgress { change(it); true }
        customActions = listOf(CustomAccessibilityAction(strings.text(R.string.native_50407ad8dfa8)) { change(actual - .05f); true }, CustomAccessibilityAction(strings.text(R.string.native_b72c5c02a666)) { change(actual + .05f); true })
    }.onKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.DirectionLeft, Key.DirectionUp -> { change(actual - .025f); true }
            Key.DirectionRight, Key.DirectionDown -> { change(actual + .025f); true }; else -> false
        }
    }.focusable()
        .pointerHoverIcon(PointerIcon(android.view.PointerIcon.getSystemIcon(context, if (vertical) android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW else android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)))
        .pointerInput(vertical, usable, range, density) {
            var dragged = 0f
            detectDragGestures(onDragStart = { dragged = latestFraction }, onDrag = { event, delta ->
                event.consume()
                if (usable > 0) { dragged = (dragged + (if (vertical) delta.y else delta.x) / (usable * density)).coerceIn(range); latestUpdate(dragged) }
            })
        }, contentAlignment = Alignment.Center) {
        Box((if (vertical) Modifier.width(38.dp).height(3.dp) else Modifier.width(3.dp).height(38.dp)).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)))
    }
}
