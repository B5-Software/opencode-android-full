package io.github.opencodeandroid.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

internal data class WorkstationExport(val id: String, val bytes: Long, val created: Long)
internal data class WorkstationReview(val id: String, val created: Long, val entries: Long, val bytes: Long,
    val version: String?, val desktopCompatible: Boolean)
internal data class WorkstationBackupStatus(val busy: Boolean = false, val cancellable: Boolean = false,
    val message: String = "", val bytes: Long = 0, val review: WorkstationReview? = null,
    val export: WorkstationExport? = null, val revision: Long = 0, val restartRequired: Boolean = false,
    val environmentRevision: Long = 0)

internal object WorkstationBackupState {
    internal data class Request(val uri: Uri?, val password: CharArray)
    private val requests = mutableMapOf<String, Request>()
    private val mutable = MutableStateFlow(WorkstationBackupStatus())
    val current = mutable.asStateFlow()
    @Synchronized fun change(edit: (WorkstationBackupStatus) -> WorkstationBackupStatus) { mutable.value = edit(mutable.value) }
    @Synchronized fun take(id: String?) = id?.let(requests::remove)
    fun submit(context: Context, importing: Boolean, uri: Uri?, password: CharArray) {
        val id = UUID.randomUUID().toString()
        synchronized(this) {
            if (mutable.value.busy || mutable.value.review != null || mutable.value.restartRequired) { password.fill('\u0000'); return }
            requests[id] = Request(uri, password)
            mutable.value = mutable.value.copy(busy = true, cancellable = true, bytes = 0, message = if (importing) "正在准备解密验证…" else "正在准备加密备份…")
        }
        try { context.startForegroundService(Intent(context, WorkstationBackupService::class.java)
            .setAction(if (importing) WorkstationBackupService.IMPORT else WorkstationBackupService.EXPORT).putExtra("requestId", id)) }
        catch (_: Exception) {
            take(id)?.password?.fill('\u0000')
            change { it.copy(busy = false, cancellable = false, message = "无法启动备份服务，请在前台重试。") }
        }
    }
    fun acknowledgeRestart() = change { it.copy(restartRequired = false) }
    fun environmentChanged(message: String) = change {
        it.copy(environmentRevision = it.environmentRevision + 1, restartRequired = true, message = message)
    }
}
