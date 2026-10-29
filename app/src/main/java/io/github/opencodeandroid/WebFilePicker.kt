package io.github.opencodeandroid

import io.github.opencodeandroid.i18n.nativeMessage

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

interface WebFileChooserOwner { val webFilePicker: WebFilePicker }

/** SAF grants access only to chosen files; no storage/media permission is requested. */
class WebFilePicker(private val activity: ComponentActivity) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var owner: WebView? = null
    private var multiple = false
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selected = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uris = buildList {
                data?.clipData?.let { clip -> for (index in 0 until clip.itemCount.coerceAtMost(64)) add(clip.getItemAt(index).uri) }
                data?.data?.let(::add)
            }.distinct().filter { uri ->
                uri.scheme == "content" && activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
            (if (multiple) uris else uris.take(1)).toTypedArray().takeIf { it.isNotEmpty() }
        } else null
        val receiver = callback
        callback = null; owner = null
        receiver?.onReceiveValue(selected)
    }

    fun show(view: WebView, receiver: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        cancel()
        if (activity.isFinishing || activity.isDestroyed) { receiver.onReceiveValue(null); return true }
        callback = receiver; owner = view
        multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val accepted = params.acceptTypes.orEmpty().flatMap { it.split(',') }.mapNotNull { item ->
            val value = item.trim().lowercase(Locale.ROOT)
            when {
                value.startsWith('.') -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(value.drop(1))
                value.matches(Regex("[a-z0-9.+*-]+/[a-z0-9.+*-]+")) -> value
                else -> null
            }
        }.distinct().ifEmpty { listOf("*/*") }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = if (accepted.size == 1) accepted.single() else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { launcher.launch(intent) }
        catch (_: android.content.ActivityNotFoundException) { cancel(); Toast.makeText(activity, activity.nativeMessage("系统文件选择器不可用"), Toast.LENGTH_SHORT).show() }
        return true
    }

    fun cancel(view: WebView? = null) {
        if (view != null && owner !== view) return
        val receiver = callback
        callback = null; owner = null
        receiver?.onReceiveValue(null)
    }
}
