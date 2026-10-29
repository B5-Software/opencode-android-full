package io.github.opencodeandroid.runtime.update

import io.github.opencodeandroid.i18n.nativeMessage

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.*
import kotlinx.coroutines.*

/** Only native, explicit confirmation can start this non-exported maintenance service. */
class OpenCodeUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { if (OpenCodeUpdateState.current.value.cancellable) job?.cancel(); return START_NOT_STICKY }
        if (job?.isCompleted == false) return START_NOT_STICKY
        val rollback = intent?.action == ROLLBACK
        if (!rollback && intent?.action != INSTALL) { stopSelf(); return START_NOT_STICKY }
        val candidate = OpenCodeUpdateState.current.value.candidate
        if (!rollback && (candidate == null || candidate.metadata.manifestSha256 != intent?.getStringExtra("confirmedManifest"))) { stopSelf(); return START_NOT_STICKY }
        if (!OpenCodeUpdateState.reserve("正在准备 OpenCode 更新…")) { stopSelf(); return START_NOT_STICKY }
        val lease = try { RuntimeCoordination.claimMaintenance() } catch (_: Exception) {
            OpenCodeUpdateState.finished("请先停止 OpenCode、终端和桌面，并等待它们退出。"); stopSelf(); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("opencode-update", nativeMessage("OpenCode 更新"), NotificationManager.IMPORTANCE_LOW))
            val pending = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "opencode-update").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(nativeMessage("OpenCode 更新")).setContentText(nativeMessage("正在保存状态、验证程序和处理版本切换")).setOngoing(true).setContentIntent(pending).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(3, notification)
        } catch (_: Exception) { lease.close(); OpenCodeUpdateState.finished("无法启动更新维护服务。"); stopSelf(); return START_NOT_STICKY }
        job = scope.launch {
            val paths = RuntimePaths(this@OpenCodeUpdateService)
            var completed = false
            var beforeKey: String? = null
            try {
                paths.prepare()
                val manager = OpenCodeUpdates(paths) { point ->
                    when (point) {
                        "extracting" -> { ensureActive(); OpenCodeUpdateState.progress("正在验证并暂存官方程序…", true) }
                        "retaining-baseline" -> { ensureActive(); OpenCodeUpdateState.progress("正在保留原程序及发布者签名…", true) }
                        "snapshotting" -> { ensureActive(); OpenCodeUpdateState.progress("正在保存会话状态快照…", true) }
                        "prepared" -> { ensureActive(); OpenCodeUpdateState.progress("正在切换版本并验证本地服务…") }
                        "rollback-prepared" -> OpenCodeUpdateState.progress("正在恢复更新前的版本与会话…")
                    }
                }
                RuntimeRecovery.recover(paths, lease, RuntimeState::log)
                beforeKey = manager.browserKey()
                RuntimeState.paired(null); RuntimeState.update(if (rollback) "正在恢复 OpenCode" else "正在更新 OpenCode", true)
                if (rollback) manager.rollback(lease)
                else manager.install(candidate!!.file, candidate.metadata.manifestSha256, lease) { metadata ->
                    OpenCodeUpdateProbe.run(paths, metadata, checkpoint = { ensureActive() })
                }
                completed = true
                if (!rollback) runCatching { OpenCodeUpdateState.forgetCandidate(this@OpenCodeUpdateService) }
                OpenCodeUpdateState.finished(if (rollback) "已恢复更新前的版本与会话，较新的会话仍保存在私有备份中。" else "OpenCode 更新完成，启动后使用匹配的官方前后端。")
            } catch (_: CancellationException) { OpenCodeUpdateState.finished("已取消更新，正在确认原状态已恢复。") }
            catch (_: Exception) { OpenCodeUpdateState.finished("更新未完成，正在恢复。原程序与会话备份会保留。") }
            finally {
                withContext(NonCancellable) {
                    var recovered = true
                    try { OpenCodeUpdates(paths).recover(lease) } catch (_: Exception) {
                        recovered = false
                        OpenCodeUpdateState.finished("恢复尚未完成，所有状态目录均已保留。请保留数据并重试启动以恢复。")
                    }
                    if (!completed && recovered) {
                        val changed = beforeKey != null && runCatching { OpenCodeUpdates(paths).browserKey() != beforeKey }.getOrDefault(false)
                        OpenCodeUpdateState.finished(if (rollback && changed) "已完成会话恢复；较新的状态保留在备份中。" else if (rollback) "未能恢复：备份或原程序条件不满足，当前会话与备份保持不变。" else "更新已取消或失败，原版本与会话已恢复。")
                    }
                    RuntimeState.update(if (recovered) "Stopped" else "Needs attention", false, paths.installed)
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        job!!.invokeOnCompletion { lease.close() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        const val INSTALL = "io.github.opencodeandroid.INSTALL_OPENCODE_UPDATE"
        const val ROLLBACK = "io.github.opencodeandroid.ROLLBACK_OPENCODE_UPDATE"
        const val CANCEL = "io.github.opencodeandroid.CANCEL_OPENCODE_UPDATE"
    }
}
