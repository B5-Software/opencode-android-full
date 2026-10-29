package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.desktop.DesktopState

@Composable
internal fun DevelopmentRecoveryControls(onAction: (String) -> Unit) {
    val context = LocalContext.current
    val status by DevelopmentSessions.current.collectAsStateWithLifecycle()
    val runtime by RuntimeState.status.collectAsStateWithLifecycle()
    val desktop by DesktopState.status.collectAsStateWithLifecycle()
    DevelopmentRecoveryPanel(status, runtime, desktop.busy, { onAction(RuntimeService.START) }, { DevelopmentSessions.reset(context) })
}

@Composable
internal fun DevelopmentRecoveryPanel(status: DevelopmentRecoverySnapshot, runtime: RuntimeSnapshot, desktopBusy: Boolean,
    onRestart: () -> Unit, onRepair: () -> Unit) {
    val strings = rememberNativeStrings()
    val history = status.history
    if (!status.error && history.runtime.interrupted == null && history.desktop.interrupted == null) return
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.text(R.string.native_a62123cde9c7), style = MaterialTheme.typography.titleMedium)
            if (status.error) {
                Text(strings.text(R.string.native_279308947a14), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onRepair, enabled = !runtime.busy && !desktopBusy) { Text(strings.text(R.string.native_2bd92fae24e5)) }
            } else {
                for (kind in DevelopmentKind.entries) history.slot(kind).interrupted?.let { run ->
                    Text(strings.message(run.operation.title), style = MaterialTheme.typography.titleSmall)
                    Text(if (run.failed) strings.text(R.string.native_6b43650c2c82) else strings.message(DevelopmentSessions.reason(run.exitReason)), style = MaterialTheme.typography.bodySmall)
                    if (run.terminals.isNotEmpty()) Text(strings.text(R.string.native_acb0c970aaa6, run.terminals.size), style = MaterialTheme.typography.bodySmall)
                    if (history.slot(kind).acknowledged) Text(strings.text(R.string.native_4ba153d8dff5), style = MaterialTheme.typography.bodySmall)
                }
                Text(strings.text(R.string.native_f03a2adbf5cd), style = MaterialTheme.typography.bodySmall)
                if (history.needsRestart(DevelopmentKind.RUNTIME)) Button(onRestart, enabled = runtime.ready && !runtime.busy && !desktopBusy) { Text(strings.text(R.string.native_fa9669296762)) }
                if (history.needsRestart(DevelopmentKind.DESKTOP)) Text(strings.text(R.string.native_bd8130bec5d9), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
