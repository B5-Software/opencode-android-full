package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.rememberNativeStrings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.opencodeandroid.desktop.DesktopService
import io.github.opencodeandroid.desktop.DesktopState
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.runtime.update.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OpenCodeUpdateControls() {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val update by OpenCodeUpdateState.current.collectAsStateWithLifecycle()
    val runtime by RuntimeState.status.collectAsStateWithLifecycle()
    val desktop by DesktopState.status.collectAsStateWithLifecycle()
    var version by remember { mutableStateOf(strings.text(R.string.native_6c5d2fe4f42a)) }
    var rollbackAvailable by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf(OpenCodeFetchService.source(context)) }
    LaunchedEffect(update.revision, runtime.busy) {
        withContext(Dispatchers.IO) {
            runCatching { OpenCodeUpdates(RuntimePaths(context)).let { it.active()?.second?.version to it.hasRollback() } }
                .onSuccess { version = it.first ?: strings.text(R.string.native_6c5d2fe4f42a); rollbackAvailable = it.second }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) OpenCodeUpdateState.import(context, uri) }
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.text(R.string.native_e6b986511faf), style = MaterialTheme.typography.titleMedium)
            Text(version, style = MaterialTheme.typography.bodyMedium)
            Text(strings.text(R.string.native_36ab16248f65), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(source, { if (it.length <= 4096) source = it }, enabled = !update.busy,
                label = { Text(strings.text(R.string.native_8aad48b0508d)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({
                context.startForegroundService(Intent(context, OpenCodeFetchService::class.java).setAction(OpenCodeFetchService.CHECK).putExtra("source", source.trim()))
            }, enabled = !update.busy && source.isNotBlank()) { Text(strings.text(R.string.native_7932c7a883eb)) }
            update.feed?.takeIf { update.feedSource == source.trim() }?.let { feed ->
                Text(strings.text(R.string.native_f73973cfadbb, feed.version, feed.commit.take(12), feed.packageSize / 1048576), style = MaterialTheme.typography.bodySmall)
                OutlinedButton({
                    context.startForegroundService(Intent(context, OpenCodeFetchService::class.java).setAction(OpenCodeFetchService.DOWNLOAD).putExtra("feedIdentity", feed.identity))
                }, enabled = !update.busy) { Text(strings.text(R.string.native_a9fe3a2301f5)) }
            }
            OutlinedButton({ picker.launch(arrayOf("*/*")) }, enabled = !update.busy) { Text(strings.text(R.string.native_f2ef7965bf12)) }
            update.candidate?.let { candidate ->
                Text(strings.text(R.string.native_165111adfe2c, candidate.metadata.version, candidate.metadata.commit.take(12)), style = MaterialTheme.typography.bodyMedium)
                Button({ confirmation = "install" }, enabled = !update.busy && runtime.ready && !runtime.busy && !desktop.busy) { Text(strings.text(R.string.native_fdf1c88487d1)) }
            }
            if (rollbackAvailable) OutlinedButton({ confirmation = "rollback" }, enabled = !update.busy && !runtime.busy && !desktop.busy) { Text(strings.text(R.string.native_f9432c4d9b1e)) }
            if ((update.candidate != null || rollbackAvailable) && (runtime.busy || desktop.busy) && !update.busy) {
                TextButton({
                    if (runtime.busy) context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
                    if (desktop.busy) context.startService(Intent(context, DesktopService::class.java).setAction(DesktopService.STOP))
                }) { Text(strings.text(R.string.native_0559cd596453)) }
            }
            if (update.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (update.message.isNotEmpty()) Text(update.message, style = MaterialTheme.typography.bodySmall)
            if (update.cancellable) TextButton({
                val intent = if (update.network) Intent(context, OpenCodeFetchService::class.java).setAction(OpenCodeFetchService.CANCEL)
                    else Intent(context, OpenCodeUpdateService::class.java).setAction(OpenCodeUpdateService.CANCEL)
                context.startService(intent)
            }) { Text(strings.text(R.string.native_6864335de653)) }
        }
    }
    confirmation?.let { action ->
        val candidate = update.candidate
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(if (action == "install") strings.text(R.string.native_5e3472cf084e) else strings.text(R.string.native_816bf3635d53)) },
            text = { Text(if (action == "install") strings.text(R.string.native_11beb4a42abc, candidate?.metadata?.version.orEmpty()) else strings.text(R.string.native_bc7b4149e7d2)) },
            confirmButton = { TextButton({
                confirmation = null
                context.mainActivity()?.retainedBrowser?.trim()
                val intent = Intent(context, OpenCodeUpdateService::class.java).setAction(if (action == "install") OpenCodeUpdateService.INSTALL else OpenCodeUpdateService.ROLLBACK)
                if (action == "install") intent.putExtra("confirmedManifest", candidate?.metadata?.manifestSha256)
                context.startForegroundService(intent)
            }, enabled = !update.busy && !runtime.busy && !desktop.busy && (action != "install" || candidate != null)) { Text(if (action == "install") strings.text(R.string.native_def5e273616d) else strings.text(R.string.native_ddf09383413c)) } },
            dismissButton = { TextButton({ confirmation = null }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
    }
}
