package io.github.opencodeandroid.files

import io.github.opencodeandroid.i18n.rememberNativeStrings
import io.github.opencodeandroid.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.opencodeandroid.WorkspaceGlyph

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ProjectFilesPage(model: ProjectFilesViewModel, onOpenCode: ((String) -> Unit)? = null, onOpenTerminal: ((String) -> Unit)? = null) {
    val strings = rememberNativeStrings()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var create by rememberSaveable { mutableStateOf<String?>(null) }
    var actionEntry by remember { mutableStateOf<ProjectEntry?>(null) }
    var rename by remember { mutableStateOf<ProjectEntry?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntry?>(null) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var importDirectory by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    val embedded = io.github.opencodeandroid.LocalWorkspacePane.current
    var quickActions by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) model.import(it, importDirectory) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = exportPath; exportPath = null
        if (uri != null && path != null) model.export(path, uri)
    }
    LaunchedEffect(Unit) { model.refresh() }
    LaunchedEffect(state.share, lifecycle) {
        val uri = state.share ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (model.consumeShare(uri)) {
                try { context.startActivity(FileSharing.intent(context, uri)) }
                catch (_: android.content.ActivityNotFoundException) { model.reportError(strings.text(R.string.native_46eae321049e)) }
            }
        }
    }
    Column(Modifier.fillMaxSize().displayCutoutPadding().padding(horizontal = if (embedded) 8.dp else 18.dp)) {
        Spacer(Modifier.height(if (embedded) 8.dp else 20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(if (state.document != null) state.document!!.path.substringAfterLast('/') else if (state.trashOpen) strings.text(R.string.native_87d699d3a26c) else strings.text(R.string.native_d0a8c03d6c07), style = if (embedded) MaterialTheme.typography.titleSmall else MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.trashOpen) strings.text(R.string.native_4dcf914f0b64) else "/workspace" + (if (state.directory.isEmpty()) "" else "/${state.directory}"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.document != null || state.directory.isNotEmpty() || state.trashOpen) TextButton({ model.back() }, enabled = !state.busy) { Text(strings.text(R.string.native_c5b81b02354f)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        if (state.busy && state.canCancel) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(state.progress?.let { strings.text(R.string.native_e56b99379169, it.files, sizeLabel(it.bytes)) } ?: strings.text(R.string.native_00d9f8ffd4a2), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            TextButton(model::cancelOperation) { Text(strings.text(R.string.native_85199bb36b04)) }
        }
        if (state.error != null || state.message != null) {
            Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), color = if (state.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(strings.message(state.error ?: state.message!!), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(model::clearMessage, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(strings.text(R.string.native_e9dc52d1c24b)) }
                }
            }
        }
        if (!state.trashOpen && (onOpenCode != null || onOpenTerminal != null)) Row(Modifier.fillMaxWidth().height(if (embedded) 36.dp else 44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onOpenCode != null) TextButton({ onOpenCode(state.directory) }, enabled = !state.busy && !state.dirty, modifier = Modifier.testTag("files-open-code"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (embedded) strings.text(R.string.native_4e1e5aacac7f) else strings.text(R.string.native_23c250cc8e0b)) }
            if (onOpenTerminal != null) TextButton({ onOpenTerminal(state.directory) }, enabled = !state.busy, modifier = Modifier.testTag("files-open-terminal"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(strings.text(R.string.native_f22da21fd54f)) }
        }
        when {
            state.document != null -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (state.dirty) strings.text(R.string.native_3645f30b3412) else "UTF-8", Modifier.padding(vertical = 15.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Button(model::save, enabled = !state.busy && state.dirty, modifier = Modifier.testTag("files-save")) { Text(strings.text(R.string.native_2266227605c4)) }
                }
                Surface(Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium) {
                    BasicTextField(state.draft, model::edit, Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()).testTag("files-editor"), enabled = !state.busy,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace), cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary))
                }
            }
            state.trashOpen -> LazyColumn(Modifier.weight(1f)) {
                if (state.trash.isEmpty()) item { Text(strings.text(R.string.native_064fbc7ad3f3), Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.trash, key = { it.id }) { entry ->
                    ListItem(headlineContent = { Text(entry.path.substringAfterLast('/')) }, supportingContent = { Text(entry.path, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingContent = { TextButton({ model.restore(entry) }, enabled = !state.busy) { Text(strings.text(R.string.native_a9e739b13e52)) } })
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
            else -> {
                state.clipboard?.let { clipboard ->
                    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text((if (clipboard.move) strings.text(R.string.native_45985dc2183e) else strings.text(R.string.native_31214130b5b7)) + clipboard.entry.name, Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(model::paste, enabled = !state.busy) { Text(strings.text(R.string.native_07e5c3158f35)) }
                            TextButton(model::cancelTransfer, enabled = !state.busy) { Text(strings.text(R.string.native_0eddf4ad9b94)) }
                        }
                    }
                }
                if (embedded) Row(Modifier.fillMaxWidth().height(40.dp)) {
                    Box {
                        TextButton({ quickActions = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(strings.text(R.string.native_831b4e0fb574)) }
                        DropdownMenu(quickActions, { quickActions = false }) {
                            DropdownMenuItem({ Text(strings.text(R.string.native_ff65a22d7087)) }, { create = "folder"; quickActions = false }, enabled = !state.busy)
                            DropdownMenuItem({ Text(strings.text(R.string.native_71faed44a86c)) }, { create = "file"; quickActions = false }, enabled = !state.busy)
                            DropdownMenuItem({ Text(strings.text(R.string.native_87d699d3a26c)) }, { model.openTrash(); quickActions = false }, enabled = !state.busy)
                        }
                    }
                    TextButton({ importDirectory = state.directory; importer.launch(arrayOf("*/*")) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(strings.text(R.string.native_8dddd460f6ae)) }
                    TextButton(model::refresh, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(strings.text(R.string.native_d85825123e60)) }
                } else FlowRow(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ create = "folder" }, enabled = !state.busy) { Text(strings.text(R.string.native_ff65a22d7087)) }
                    FilledTonalButton({ create = "file" }, enabled = !state.busy) { Text(strings.text(R.string.native_71faed44a86c)) }
                    OutlinedButton({ importDirectory = state.directory; importer.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text(strings.text(R.string.native_8dddd460f6ae)) }
                    TextButton(model::openTrash, enabled = !state.busy) { Text(strings.text(R.string.native_87d699d3a26c)) }
                    TextButton(model::refresh, enabled = !state.busy) { Text(strings.text(R.string.native_d85825123e60)) }
                }
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), placeholder = { Text(strings.text(R.string.native_ac9d73843736)) }, singleLine = true)
                val entries = state.entries.filter { it.name.contains(search, ignoreCase = true) }
                LazyColumn(Modifier.weight(1f).testTag("files-list")) {
                    if (entries.isEmpty()) item { Text(if (search.isBlank()) strings.text(R.string.native_21eef82963ee) else strings.text(R.string.native_e8a4cecaa371), Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(entries, key = { it.path }) { entry ->
                        ListItem(modifier = Modifier.pointerInput(entry.path, state.busy) {
                            if (!state.busy) awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.buttons.isSecondaryPressed && event.changes.any { it.changedToDownIgnoreConsumed() }) {
                                        event.changes.forEach { it.consume() }
                                        actionEntry = entry
                                    }
                                }
                            }
                        }.combinedClickable(enabled = !state.busy, onClick = { model.open(entry) }, onLongClickLabel = strings.text(R.string.native_d674bc0f86b8), onLongClick = { actionEntry = entry }),
                            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(if (entry.link) strings.text(R.string.native_d04a47c87cc8) else if (entry.directory) strings.text(R.string.native_e0ba66f5246a) else sizeLabel(entry.size), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { WorkspaceGlyph(if (entry.directory) 3 else 0, MaterialTheme.colorScheme.primary, Modifier.size(if (embedded) 18.dp else 24.dp)) },
                            trailingContent = { TextButton({ actionEntry = entry }, enabled = !state.busy, modifier = (if (embedded) Modifier.width(32.dp) else Modifier).testTag("file-actions-${entry.name}"), contentPadding = PaddingValues(0.dp)) { Text("⋮") } })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
                    }
                    item { Spacer(Modifier.height(90.dp)) }
                }
            }
        }
    }
    if (create != null) NameDialog(if (create == "folder") strings.text(R.string.native_ff65a22d7087) else strings.text(R.string.native_71faed44a86c), "", { create = null }) { name -> model.create(name, create == "folder"); create = null }
    rename?.let { entry -> NameDialog(strings.text(R.string.native_f170b42599e8), entry.name, { rename = null }) { name -> model.rename(entry, name); rename = null } }
    actionEntry?.let { entry ->
        AlertDialog(onDismissRequest = { actionEntry = null }, title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton({ rename = entry; actionEntry = null }) { Text(strings.text(R.string.native_f170b42599e8)) }
                TextButton({ model.selectTransfer(entry, false); actionEntry = null }) { Text(strings.text(R.string.native_4cdacf57ead2)) }
                TextButton({ model.selectTransfer(entry, true); actionEntry = null }) { Text(strings.text(R.string.native_124aa030694e)) }
                if (entry.directory && !entry.link) {
                    if (onOpenCode != null) TextButton({ onOpenCode(entry.path); actionEntry = null }) { Text(strings.text(R.string.native_23c250cc8e0b)) }
                    if (onOpenTerminal != null) TextButton({ onOpenTerminal(entry.path); actionEntry = null }) { Text(strings.text(R.string.native_ac990518ed87)) }
                }
                if (!entry.directory && !entry.link) TextButton({ exportPath = entry.path; actionEntry = null; exporter.launch(entry.name) }) { Text(strings.text(R.string.native_87bc93932d8a)) }
                if (!entry.directory && !entry.link) TextButton({ model.share(entry); actionEntry = null }) { Text(strings.text(R.string.native_9dfb5569d609)) }
                TextButton({ deleting = entry; actionEntry = null }) { Text(strings.text(R.string.native_dd37f132172f), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ actionEntry = null }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
    }
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(strings.text(R.string.native_7318746d337c)) }, text = { Text(strings.text(R.string.native_2b5ee3f10a56, entry.name)) }, confirmButton = { TextButton({ model.trash(entry); deleting = null }) { Text(strings.text(R.string.native_e49730c9e7e1)) } }, dismissButton = { TextButton({ deleting = null }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } }) }
    if (state.confirmClose) AlertDialog(onDismissRequest = model::cancelClose, title = { Text(strings.text(R.string.native_4e47aaa113fc)) }, confirmButton = { TextButton(model::discard) { Text(strings.text(R.string.native_63b75399ef1a)) } }, dismissButton = { TextButton(model::cancelClose) { Text(strings.text(R.string.native_d9618f7b8d25)) } })
}

@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    val strings = rememberNativeStrings()
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text(strings.text(R.string.native_76ce6aef5479)) }, singleLine = true, modifier = Modifier.testTag("files-name")) },
        confirmButton = { TextButton({ confirm(name) }, enabled = name.isNotBlank()) { Text(strings.text(R.string.native_35b0fc30b909)) } }, dismissButton = { TextButton(dismiss) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
}

private fun sizeLabel(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024))
}
