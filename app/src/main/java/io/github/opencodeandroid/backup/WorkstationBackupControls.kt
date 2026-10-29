package io.github.opencodeandroid.backup

import io.github.opencodeandroid.i18n.rememberNativeStrings
import io.github.opencodeandroid.R

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.desktop.DesktopService
import io.github.opencodeandroid.desktop.DesktopState
import io.github.opencodeandroid.mainActivity
import io.github.opencodeandroid.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkstationBackupControls(hasUnsavedChanges: Boolean, filesBusy: Boolean, onRecover: () -> Unit) {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val state by WorkstationBackupState.current.collectAsStateWithLifecycle()
    val runtime by RuntimeState.status.collectAsStateWithLifecycle()
    val desktop by DesktopState.status.collectAsStateWithLifecycle()
    val maintenance by RuntimeCoordination.maintenanceLabel.collectAsStateWithLifecycle()
    var canUndo by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var passwordMode by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceUri by rememberSaveable { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var confirmUndo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.revision, runtime.busy, runtime.ready, maintenance) {
        withContext(Dispatchers.IO) {
            val paths = RuntimePaths(context)
            pending = RuntimeRecovery.pending(paths)
            canUndo = WorkstationRestore(paths, PortableSettings(context)).canUndo()
            runCatching { WorkstationExports(paths).ready() }.onSuccess { exported ->
                WorkstationBackupState.change { it.copy(export = exported) }
            }
        }
    }
    val reader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { sourceUri = uri.toString(); passwordMode = "import"; password = ""; repeated = "" }
    }
    val writer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) context.startForegroundService(Intent(context, WorkstationBackupService::class.java).setAction(WorkstationBackupService.SAVE).setData(uri))
    }
    val quiet = !runtime.busy && !desktop.busy && !filesBusy && maintenance == null && !state.busy && !state.restartRequired
    val editable = quiet && !hasUnsavedChanges && !pending
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.text(R.string.native_0daee8026db1), style = MaterialTheme.typography.titleMedium)
            Text(strings.text(R.string.native_1acc18928c5f), style = MaterialTheme.typography.bodySmall)
            Text(strings.text(R.string.native_62254d31a535), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ passwordMode = "export"; password = ""; repeated = "" }, enabled = editable, modifier = Modifier.testTag("workstation-export")) { Text(strings.text(R.string.native_808eabc7811f)) }
                OutlinedButton({ reader.launch(arrayOf("*/*")) }, enabled = editable, modifier = Modifier.testTag("workstation-import")) { Text(strings.text(R.string.native_201c10b38296)) }
            }
            if (hasUnsavedChanges) Text(strings.text(R.string.native_b0e4e4eb7ebd), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (runtime.busy || desktop.busy) TextButton({
                if (runtime.busy) context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
                if (desktop.busy) context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP))
            }) { Text(strings.text(R.string.native_0559cd596453)) }
            if (pending) {
                Text(strings.text(R.string.native_fdf2be8e3b93), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onRecover, enabled = quiet) { Text(strings.text(R.string.native_185f58156330)) }
            }
            if (canUndo) OutlinedButton({ confirmUndo = true }, enabled = editable, modifier = Modifier.testTag("workstation-undo")) { Text(strings.text(R.string.native_20831b6f67a7)) }
            state.export?.let { info ->
                Text(strings.text(R.string.native_4f392d5d6141, android.text.format.Formatter.formatShortFileSize(context, info.bytes)), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ writer.launch("opencode-workstation-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(Date(info.created))}.ocawork") }, enabled = !state.busy && state.review == null) { Text(strings.text(R.string.native_80077c694212)) }
                    TextButton({ confirmDelete = true }, enabled = !state.busy && state.review == null) { Text(strings.text(R.string.native_f09619351eb9)) }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.message.isNotEmpty()) Text(strings.message(state.message), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("workstation-status"))
            if (state.bytes > 0) Text(strings.text(R.string.native_85c686eb9841, android.text.format.Formatter.formatShortFileSize(context, state.bytes)), style = MaterialTheme.typography.bodySmall)
            if (state.cancellable && state.busy) TextButton({ context.startService(Intent(context, WorkstationBackupService::class.java).setAction(WorkstationBackupService.CANCEL)) }) { Text(strings.text(R.string.native_28d567b926cc)) }
        }
    }
    passwordMode?.let { mode ->
        val exporting = mode == "export"
        AlertDialog(onDismissRequest = { passwordMode = null; password = ""; repeated = "" }, title = { Text(if (exporting) strings.text(R.string.native_66c9d896407b) else strings.text(R.string.native_548cd9f0831e)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (exporting) strings.text(R.string.native_d248f5eca9a5) else strings.text(R.string.native_f249422b9ec1))
                    OutlinedTextField(password, { password = it }, label = { Text(strings.text(R.string.native_e7be6841703a)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("workstation-password"))
                    if (exporting) OutlinedTextField(repeated, { repeated = it }, label = { Text(strings.text(R.string.native_9f6aeedc5a00)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.testTag("workstation-password-repeat"))
                }
            },
            confirmButton = { TextButton({
                context.mainActivity()?.retainedBrowser?.trim()
                val secret = password.toCharArray(); password = ""; repeated = ""; passwordMode = null
                WorkstationBackupState.submit(context, !exporting, if (exporting) null else android.net.Uri.parse(sourceUri), secret)
            }, enabled = editable && password.length >= (if (exporting) 12 else 1) && (!exporting || password == repeated)) { Text(if (exporting) strings.text(R.string.native_eafdc5cd4882) else strings.text(R.string.native_2986b88beade)) } },
            dismissButton = { TextButton({ passwordMode = null; password = ""; repeated = "" }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
    }
    if (confirmUndo || confirmDelete) AlertDialog(onDismissRequest = { confirmUndo = false; confirmDelete = false },
        title = { Text(if (confirmUndo) strings.text(R.string.native_f46c5ed7412d) else strings.text(R.string.native_33eda0946a5b)) },
        text = { Text(if (confirmUndo) strings.text(R.string.native_26db24150f2e) else strings.text(R.string.native_1153b30d51d5)) },
        confirmButton = { TextButton({
            context.mainActivity()?.retainedBrowser?.trim()
            context.startForegroundService(Intent(context, WorkstationBackupService::class.java).setAction(if (confirmUndo) WorkstationBackupService.UNDO else WorkstationBackupService.DISCARD_EXPORT))
            confirmUndo = false; confirmDelete = false
        }, enabled = if (confirmUndo) editable else !state.busy) { Text(strings.text(R.string.native_427a78a4e69a)) } },
        dismissButton = { TextButton({ confirmUndo = false; confirmDelete = false }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
}

/** Global dialogs prevent stale editors or another native pane from writing during a root switch. */
@Composable
internal fun WorkstationBackupDialogs(hasUnsavedChanges: Boolean, staleEditor: () -> Boolean = { false }) {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val state by WorkstationBackupState.current.collectAsStateWithLifecycle()
    val maintenance by RuntimeCoordination.maintenanceLabel.collectAsStateWithLifecycle()
    state.review?.let { review ->
        var compatible by remember(review.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { context.startService(Intent(context, WorkstationBackupService::class.java).setAction(WorkstationBackupService.CANCEL)) },
            title = { Text(strings.text(R.string.native_7af46d7ee003)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.text(R.string.native_ed40940162d0, DateFormat.getDateTimeInstance().format(Date(review.created)), review.entries, android.text.format.Formatter.formatShortFileSize(context, review.bytes), review.version ?: strings.message("未安装")))
                Text(strings.text(R.string.native_8370cb5d4818))
                if (state.message != "备份验证完成，等待确认恢复。") Text(strings.message(state.message), color = MaterialTheme.colorScheme.error)
                if (review.desktopCompatible) {
                    Row { Checkbox(compatible, { compatible = it }); Text(strings.text(R.string.native_97076078c749), Modifier.padding(top = 12.dp)) }
                    Text(strings.text(R.string.native_1bfcd9fe12e4), style = MaterialTheme.typography.bodySmall)
                }
            } },
            confirmButton = { TextButton({ context.startService(Intent(context, WorkstationBackupService::class.java).setAction(WorkstationBackupService.APPLY).putExtra("allowCompatibility", compatible)) }, enabled = !hasUnsavedChanges && !state.busy, modifier = Modifier.testTag("workstation-confirm-restore")) { Text(strings.text(R.string.native_ddf09383413c)) } },
            dismissButton = { TextButton({ context.startService(Intent(context, WorkstationBackupService::class.java).setAction(WorkstationBackupService.CANCEL)) }) { Text(strings.text(R.string.native_8e6056b0f629)) } })
        return
    }
    if (state.restartRequired || staleEditor()) {
        AlertDialog(onDismissRequest = {}, title = { Text(strings.text(R.string.native_63a895901c76)) }, text = { Text(strings.message(state.message)) },
            confirmButton = { TextButton({
                WorkstationBackupState.acknowledgeRestart(); WorkspaceContext.reload(context)
                context.mainActivity()?.retainedBrowser?.trim()
                context.startActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.OPEN_WORKSPACE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }, modifier = Modifier.testTag("workstation-reopen")) { Text(strings.text(R.string.native_a4c994348a39)) } })
    } else if (state.busy && maintenance == "工作站备份与恢复") {
        AlertDialog(onDismissRequest = {}, title = { Text(strings.text(R.string.native_0daee8026db1)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth()); Text(strings.message(state.message))
                if (state.bytes > 0) Text(strings.text(R.string.native_85c686eb9841, android.text.format.Formatter.formatShortFileSize(context, state.bytes)))
            } },
            confirmButton = { if (state.cancellable) TextButton({ context.startService(Intent(context, WorkstationBackupService::class.java).setAction(WorkstationBackupService.CANCEL)) }) { Text(strings.text(R.string.native_774947f0653e)) } })
    }
}
