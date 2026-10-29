package io.github.opencodeandroid.bridge

import io.github.opencodeandroid.i18n.rememberNativeStrings
import io.github.opencodeandroid.i18n.NativeStrings
import io.github.opencodeandroid.R

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.opencodeandroid.files.FileSharing
import io.github.opencodeandroid.preview.PreviewActivity
import io.github.opencodeandroid.preview.PreviewTarget
import org.json.JSONObject

/** Linux requests remain pending until the person chooses the Android action. */
@Composable
fun BridgeRequests() {
    val strings = rememberNativeStrings()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val requests by AndroidBridge.pending.collectAsStateWithLifecycle()
    val focus by AndroidBridge.focused.collectAsStateWithLifecycle()
    var pickerId by rememberSaveable { mutableStateOf<String?>(null) }
    var notificationId by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = pickerId; pickerId = null
        if (id != null) { if (uris.isEmpty()) AndroidBridge.cancel(id) else AndroidBridge.importFiles(id, uris) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = pickerId; pickerId = null
        if (id != null) { if (uri == null) AndroidBridge.cancel(id) else AndroidBridge.exportFile(id, uri) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val id = notificationId; notificationId = null
        if (id != null) {
            val request = AndroidBridge.pending.value.firstOrNull { it.id == id }
            if (granted && request != null) postNotification(context, request) else AndroidBridge.complete(id, "error", error = "notification_permission_denied")
        }
    }
    val request = requests.firstOrNull { it.id == focus } ?: requests.firstOrNull()
    LaunchedEffect(request?.id, request?.prepared, request?.previewPort, lifecycle) {
        val id = request?.id ?: return@LaunchedEffect
        if (request.prepared == null && request.previewPort == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                if (request.action == "preview") {
                    val port = AndroidBridge.consumePreview(id) ?: return@repeatOnLifecycle
                    context.startActivity(PreviewActivity.intent(context, port))
                    AndroidBridge.complete(id, value = JSONObject().put("url", PreviewTarget.forPort(port).url))
                } else {
                    val uri = AndroidBridge.consumePrepared(id) ?: return@repeatOnLifecycle
                    context.startActivity(FileSharing.intent(context, uri)); AndroidBridge.complete(id)
                }
            }
            catch (_: Exception) { AndroidBridge.fail(id) }
        }
    }
    if (request == null || pickerId == request.id || notificationId == request.id) return
    AlertDialog(onDismissRequest = { AndroidBridge.cancel(request.id) }, title = { Text(strings.message(title(request.action))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(strings.text(R.string.native_749d406d2c67), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(description(request, strings))
            if (request.processing) { Spacer(Modifier.height(16.dp)); LinearProgressIndicator() }
        }
    }, confirmButton = {
        TextButton(enabled = !request.processing && request.prepared == null && request.previewPort == null, onClick = {
            try {
                when (request.action) {
                    "open-url" -> {
                        val uri = Uri.parse(AndroidBridge.url(request.payload.getString("url")).toASCIIString())
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                        AndroidBridge.complete(request.id)
                    }
                    "clipboard-get" -> {
                        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
                        require(text.toByteArray().size <= 65536)
                        AndroidBridge.complete(request.id, value = JSONObject().put("text", text))
                    }
                    "clipboard-set" -> {
                        val clip = ClipData.newPlainText("Debian", request.payload.getString("text"))
                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        AndroidBridge.complete(request.id)
                    }
                    "notify" -> {
                        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationId = request.id; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else postNotification(context, request)
                    }
                    "share-file" -> AndroidBridge.prepareShare(request.id)
                    "preview" -> AndroidBridge.preparePreview(request.id)
                    "file-import" -> { pickerId = request.id; importer.launch(arrayOf("*/*")) }
                    "file-export" -> { pickerId = request.id; exporter.launch(request.payload.getString("path").substringAfterLast('/')) }
                }
            } catch (_: Exception) { AndroidBridge.fail(request.id) }
        }) { Text(strings.message(confirmLabel(request.action))) }
    }, dismissButton = { TextButton({ AndroidBridge.cancel(request.id) }) { Text(strings.text(R.string.native_0eddf4ad9b94)) } })
}

private fun title(action: String) = when (action) {
    "open-url" -> "打开系统浏览器？"
    "preview" -> "打开本地项目预览？"
    "clipboard-get" -> "读取剪贴板？"
    "clipboard-set" -> "复制到系统剪贴板？"
    "notify" -> "发送本地通知？"
    "share-file" -> "分享项目文件？"
    "file-import" -> "导入系统文件？"
    else -> "导出项目文件？"
}
private fun confirmLabel(action: String) = when (action) {
    "open-url" -> "在浏览器中打开"
    "preview" -> "打开预览"
    "clipboard-get" -> "允许读取"
    "clipboard-set" -> "复制到剪贴板"
    "notify" -> "发送通知"
    "share-file" -> "选择分享应用"
    "file-import" -> "选择文件"
    else -> "选择保存位置"
}
private fun description(request: BridgeRequest, strings: NativeStrings): String = when (request.action) {
    "open-url" -> AndroidBridge.url(request.payload.getString("url")).let { uri -> "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}${uri.rawPath.orEmpty().take(160)}" }
    "preview" -> strings.text(R.string.native_23e5bb2090aa, PreviewTarget.fromValue(request.payload.get("port")).url)
    "clipboard-get" -> strings.message("将系统剪贴板中的文字交给当前 Debian 程序。")
    "clipboard-set" -> request.payload.getString("text").take(300)
    "notify" -> request.payload.getString("title") + "\n" + request.payload.optString("text")
    "file-import" -> strings.text(R.string.native_dcc6bec24e43, request.payload.getString("directory"))
    else -> request.payload.getString("path")
}
private fun postNotification(context: Context, request: BridgeRequest) {
    try {
        context.getSystemService(NotificationManager::class.java).notify(100000 + (request.id.hashCode() and 0x7fffff), NotificationCompat.Builder(context, "android-bridge")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(request.payload.getString("title")).setContentText(request.payload.optString("text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(request.payload.optString("text"))).setAutoCancel(true).build())
        AndroidBridge.complete(request.id)
    } catch (_: Exception) { AndroidBridge.fail(request.id) }
}
