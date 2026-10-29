package io.github.opencodeandroid.backup

import io.github.opencodeandroid.i18n.nativeMessage

import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import android.system.Os
import androidx.core.app.NotificationCompat
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.runtime.update.*
import kotlinx.coroutines.*
import java.io.File

/** Non-exported user actions. An imported Linux tree is never executed before native confirmation. */
open class WorkstationBackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var paths: RuntimePaths
    private var job: Job? = null
    private var lease: RuntimeCoordination.MaintenanceLease? = null
    private data class Candidate(val stage: File, val descriptor: StagedWorkstation, val inspection: WorkstationValidation.Inspection)
    private var candidate: Candidate? = null
    @Volatile private var input: java.io.InputStream? = null
    @Volatile private var outputForCancel: java.io.OutputStream? = null
    @Volatile private var destroyed = false
    private var lastProgress = 0L
    private var wake: BackupWakeLease? = null
    protected open fun runtimeContext(): Context = this
    private fun nativeSettings() = PortableSettings(runtimeContext())
    override fun onCreate() {
        super.onCreate(); paths = RuntimePaths(runtimeContext())
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("workstation-backup", nativeMessage("工作站备份与恢复"), NotificationManager.IMPORTANCE_LOW))
    }
    private fun foreground(message: String) {
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "workstation-backup").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(nativeMessage("工作站备份与恢复")).setContentText(nativeMessage(message)).setOngoing(true).setContentIntent(open).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(4, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(4, notification)
    }
    private fun status(message: String, cancellable: Boolean) {
        WorkstationBackupState.change { it.copy(busy = true, cancellable = cancellable, message = message, bytes = 0) }
    }
    private fun progress(bytes: Long) {
        scope.ensureActive(); job?.ensureActive(); lease?.checkCurrent(); wake?.touch()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgress >= 200) { lastProgress = now; WorkstationBackupState.change { it.copy(bytes = bytes) } }
    }
    private fun finished(message: String, restart: Boolean = false) = WorkstationBackupState.change {
        it.copy(busy = false, cancellable = false, message = message, review = null, bytes = 0, revision = it.revision + 1,
            restartRequired = it.restartRequired || restart, environmentRevision = it.environmentRevision + if (restart) 1 else 0)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == CANCEL) {
            if (WorkstationBackupState.current.value.cancellable) {
                if (candidate != null) {
                    val owned = lease
                    val predecessor = job
                    val cleanup = scope.launch(start = CoroutineStart.LAZY) { predecessor?.join(); discardReview() }
                    job = cleanup
                    cleanup.invokeOnCompletion { owned?.close(); if (lease === owned) lease = null }
                    cleanup.start()
                } else if (job?.isCompleted == false) { job?.cancel(); closeStreams() }
            }
            return START_NOT_STICKY
        }
        val request = WorkstationBackupState.take(intent.getStringExtra("requestId"))
        val predecessor = if (action == APPLY && candidate != null) job else null
        if ((job?.isCompleted == false && predecessor == null) || (candidate != null && action != APPLY)) { request?.password?.fill('\u0000'); return START_NOT_STICKY }
        if (action !in setOf(EXPORT, IMPORT, APPLY, UNDO, SAVE, DISCARD_EXPORT) ||
            (action in setOf(EXPORT, IMPORT) && request == null) || (action == APPLY && candidate == null)) {
            request?.password?.fill('\u0000'); finished("任务已中断，请重新选择操作并输入密码。"); stopSelf(); return START_NOT_STICKY
        }
        if (action in setOf(EXPORT, IMPORT, APPLY, UNDO) && io.github.opencodeandroid.files.ProjectFilesViewModel.hasUnsavedEditors()) {
            request?.password?.fill('\u0000')
            WorkstationBackupState.change { it.copy(busy = false, cancellable = it.review != null, message = "请先保存所有窗口中的文件编辑内容；已有导入可取消后再重试。") }
            if (candidate == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (action in setOf(EXPORT, IMPORT, UNDO)) lease = RuntimeCoordination.claimMaintenance("工作站备份与恢复")
            foreground(if (action == APPLY) "正在恢复工作站并验证程序" else "正在处理用户选择的备份任务")
        } catch (_: Exception) {
            request?.password?.fill('\u0000'); lease?.close(); lease = null
            finished("请先停止 OpenCode、终端和桌面，并等待文件操作结束。"); stopSelf(); return START_NOT_STICKY
        }
        val ownedLease = lease
        val ownedWake = try { BackupWakeLease(this) } catch (_: Exception) {
            request?.password?.fill('\u0000'); ownedLease?.close(); lease = null
            finished("无法开始备份任务，请在前台重试。"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        wake = ownedWake
        var keepReviewLease = false
        job = scope.launch(start = CoroutineStart.LAZY) {
            var stage: File? = null
            try {
                predecessor?.join()
                lease?.let { RuntimeRecovery.recover(paths, it, RuntimeState::log, nativeSettings()) }
                when (action) {
                    EXPORT -> export(checkNotNull(request).password)
                    IMPORT -> {
                        status("正在解密并验证备份，原环境保持可恢复…", true)
                        val manager = WorkstationRestore(paths, nativeSettings())
                        val destination = manager.createStage(); stage = destination
                        val descriptor = contentResolver.openInputStream(checkNotNull(request?.uri)).use { source ->
                            check(source != null); input = source
                            WorkstationArchive.stage(source, request.password, destination, ::progress)
                        }
                        input = null; ensureActive(); lease!!.checkCurrent()
                        val inspected = WorkstationValidation.inspect(paths, destination, descriptor)
                        ensureActive(); check(!destroyed)
                        candidate = Candidate(destination, descriptor, inspected)
                        val id = destination.parentFile!!.name.removePrefix("workstation-restore-")
                        WorkstationBackupState.change { it.copy(busy = false, cancellable = true, message = "备份验证完成，等待确认恢复。", bytes = 0,
                            review = WorkstationReview(id, descriptor.metadata.getLong("createdUtcMillis"), descriptor.entries, descriptor.dataBytes, inspected.core?.version, inspected.desktopCompatible)) }
                        foreground("备份已验证，等待在应用中确认恢复")
                        keepReviewLease = true
                    }
                    APPLY -> apply(intent.getBooleanExtra("allowCompatibility", false))
                    UNDO -> undo()
                    SAVE -> save(checkNotNull(intent.data))
                    DISCARD_EXPORT -> {
                        val store = WorkstationExports(paths); store.ready()?.let(store::discard)
                        WorkstationBackupState.change { it.copy(export = null) }; finished("已删除应用内的加密暂存副本。")
                    }
                }
            } catch (_: CancellationException) { if (action == IMPORT) candidate = null; finished("已取消操作，原环境数据保留。") }
            catch (_: Exception) {
                if (action == IMPORT) candidate = null
                if (action !in setOf(APPLY, UNDO)) finished("操作未完成。请检查密码、备份完整性、Host 版本和可用空间；原环境数据保留。")
            }
            finally {
                request?.password?.fill('\u0000'); input = null; outputForCancel = null
                withContext(NonCancellable) {
                    ownedWake.close(); if (wake === ownedWake) wake = null
                    if (destroyed) { candidate = null; WorkstationBackupState.change { it.copy(review = null, busy = false, cancellable = false) } }
                    if (candidate == null) {
                        stage?.takeIf { !File(it.parentFile, "transaction.json").exists() }?.let {
                            runCatching { WorkstationRestore(paths, nativeSettings()).discardStage(it) }
                        }
                        ownedLease?.close(); if (lease === ownedLease) lease = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    }
                }
            }
        }
        val worker = job!!
        worker.invokeOnCompletion {
            ownedWake.close(); if (wake === ownedWake) wake = null
            request?.password?.fill('\u0000')
            if (!keepReviewLease || (destroyed && job === worker)) { ownedLease?.close(); if (lease === ownedLease) lease = null }
        }
        worker.start()
        return START_NOT_STICKY
    }

    private fun export(password: CharArray) {
        status("正在生成加密工作站备份…", true)
        // Public provenance is the only metadata added to an old bundled environment. User files and programs are not changed.
        if (paths.installed) runCatching {
            val published = PublishedOpenCode(paths)
            if (published.stored() == null) published.bundled()?.let { proof ->
                published.verify(File(paths.rootfs, "usr/local/bin/opencode"), proof); published.store(paths.rootfs, proof)
            }
        }
        val store = WorkstationExports(paths); val (id, directory) = store.create()
        val partial = File(directory, "partial.ocawork")
        try {
            WorkstationBackupFiles.write(paths, partial, password, lease!!, nativeSettings().snapshot(), ::progress)
            job?.ensureActive(); Os.rename(partial.path, File(directory, "workstation.ocawork").path); UpdateFiles.sync(directory)
            val info = store.publish(id); WorkstationBackupState.change { it.copy(export = info) }
            finished("加密备份已生成，请选择保存位置。")
        } catch (failure: Throwable) { partial.delete(); throw failure }
    }

    private fun apply(allowCompatibility: Boolean) {
        val chosen = checkNotNull(candidate); val manager = WorkstationRestore(paths, nativeSettings())
        val id = chosen.stage.parentFile!!.name.removePrefix("workstation-restore-")
        status("正在恢复环境并验证本地服务，请勿清除应用数据…", false)
        var applied = false
        try {
            if (!allowCompatibility) {
                val marker = File(chosen.stage, "desktop-state/chromium-compat-enabled")
                if (UpdateFiles.exists(marker)) {
                    val retained = File(chosen.stage.parentFile, "disabled-chromium-compat-enabled")
                    check(!UpdateFiles.exists(retained)); Os.rename(marker.path, retained.path); UpdateFiles.sync(marker.parentFile!!)
                }
            }
            RuntimeState.paired(null)
            manager.apply(chosen.stage, chosen.descriptor, lease!!,
                { tree, descriptor -> WorkstationValidation.inspect(paths, tree, descriptor) },
                { chosen.inspection.core?.let { OpenCodeUpdateProbe.run(paths, it, checkpoint = { job?.ensureActive() }) } })
            applied = true
        } finally {
            var recovered = true
            try { RuntimeRecovery.recover(paths, lease!!, RuntimeState::log, nativeSettings()); applied = applied || manager.currentRestoreId() == id }
            catch (_: Exception) { recovered = false }
            candidate = null
            RuntimeState.update(if (recovered) "Stopped" else "Needs attention", false, paths.installed)
            finished(if (applied && recovered) "工作站已恢复，旧环境保留在应用内备份中。请重开工作区。" else if (recovered) "恢复未通过检查，已回到旧环境；导入的数据保留在私有备份中。" else "恢复尚未完成，所有数据目录保留。请保留应用数据并重试恢复。", applied && recovered)
        }
    }

    private fun undo() {
        status("正在还原上一套工作站环境…", false)
        val manager = WorkstationRestore(paths, nativeSettings()); val before = manager.currentRestoreId()
        var undone = false
        try { manager.undo(lease!!) { tree, roots -> WorkstationValidation.environment(paths, tree, roots) }; undone = true }
        finally {
            var recovered = true
            try { RuntimeRecovery.recover(paths, lease!!, RuntimeState::log, nativeSettings()); undone = undone || (before != null && manager.currentRestoreId() != before) }
            catch (_: Exception) { recovered = false }
            RuntimeState.update(if (recovered) "Stopped" else "Needs attention", false, paths.installed)
            finished(if (undone && recovered) "已还原旧环境，恢复后的较新工作也保留在私有备份中。请重开工作区。" else if (recovered) "还原条件不满足，当前环境与备份保留。" else "还原尚未完成，所有数据目录保留。请保留应用数据并重试恢复。", undone && recovered)
        }
    }

    private fun save(uri: Uri) {
        status("正在保存加密备份…", true)
        val store = WorkstationExports(paths); val info = checkNotNull(store.ready()); var complete = false
        try {
            store.file(info).inputStream().use { source ->
                input = source
                contentResolver.openOutputStream(uri, "wt").use { output ->
                    check(output != null); outputForCancel = output; val buffer = ByteArray(128 * 1024); var count = 0L
                    while (true) { progress(count); val n = source.read(buffer); if (n < 0) break; output.write(buffer, 0, n); count += n }
                    check(count == info.bytes); output.flush()
                }
            }
            complete = true; finished("加密备份已保存；应用内副本仍可再次保存或手动删除。")
        } finally { input = null; outputForCancel = null; if (!complete) runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
    }
    private suspend fun discardReview() {
        val chosen = candidate; candidate = null; status("正在清理未确认的导入暂存…", false)
        try { if (chosen != null) WorkstationRestore(paths, nativeSettings()).discardStage(chosen.stage); finished("已取消导入，原环境保持不变。") }
        catch (_: Exception) { finished("已取消导入，原环境保持不变；部分暂存数据仍在应用私有目录中。") }
        finally { lease?.close(); lease = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun closeStreams() {
        val source = input; val destination = outputForCancel
        CoroutineScope(Dispatchers.IO).launch { runCatching { source?.close() }; runCatching { destination?.close() } }
    }
    override fun onDestroy() {
        destroyed = true; scope.cancel(); closeStreams(); wake?.close(); wake = null
        if (job?.isCompleted != false) {
            candidate = null; WorkstationBackupState.change { it.copy(review = null, busy = false, cancellable = false) }
            lease?.close(); lease = null
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val EXPORT = "io.github.opencodeandroid.EXPORT_WORKSTATION"
        const val IMPORT = "io.github.opencodeandroid.IMPORT_WORKSTATION"
        const val APPLY = "io.github.opencodeandroid.APPLY_WORKSTATION"
        const val UNDO = "io.github.opencodeandroid.UNDO_WORKSTATION"
        const val SAVE = "io.github.opencodeandroid.SAVE_WORKSTATION"
        const val DISCARD_EXPORT = "io.github.opencodeandroid.DISCARD_WORKSTATION_EXPORT"
        const val CANCEL = "io.github.opencodeandroid.CANCEL_WORKSTATION"
    }
}
