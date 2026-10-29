package io.github.opencodeandroid.runtime.update

import io.github.opencodeandroid.i18n.nativeMessage

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** User-started, bounded data transfer. Never executes or activates downloaded code. */
internal class OpenCodeFetchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    @Volatile private var client: UpdateHttpsClient? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { cancelWork(); if (worker == null) stopSelf(); return START_NOT_STICKY }
        if (worker != null) return START_NOT_STICKY
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, nativeMessage("OpenCode 更新下载"), NotificationManager.IMPORTANCE_LOW))
        try { startForeground(5, notification("正在连接更新源…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action !in listOf(CHECK, DOWNLOAD) || !OpenCodeUpdateState.reserve("正在连接更新源…", network = true)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val source = intent?.getStringExtra("source").orEmpty()
        val confirmed = intent?.getStringExtra("feedIdentity")
        val action = intent?.action
        val finalized = AtomicBoolean(false)
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var staged: File? = null
            var outcome = "更新源连接失败，请检查网络、发布者签名、Host 版本或可用空间。"
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenCodeAndroid:core-download")
            try {
                wake.setReferenceCounted(false); wake.acquire(LIMIT)
                val owner = currentCoroutineContext(); val deadline = SystemClock.elapsedRealtime() + LIMIT
                var lastProgress = 0L
                val verifier = SignedOpenCodeUpdate(this@OpenCodeFetchService)
                val network = UpdateHttpsClient(checkpoint = {
                    owner.ensureActive(); check(SystemClock.elapsedRealtime() < deadline) { "下载超时" }
                }, progress = { bytes, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (action == DOWNLOAD && now - lastProgress > 500) {
                        lastProgress = now
                        val message = "正在下载：${bytes / 1048576} / ${(total ?: 0L) / 1048576} MiB"
                        OpenCodeUpdateState.progress(message, true); notifications.notify(5, notification(message))
                    }
                }); client = network
                OpenCodeUpdateState.progress(if (action == CHECK) "正在验证发布者签名…" else "正在下载更新包…", true)
                if (action == CHECK) {
                    UpdateHttpsUrl.parse(source)
                    val host = packageManager.getPackageInfo(packageName, 0).longVersionCode
                    val feed = network.feed(source, verifier, host)
                    owner.ensureActive()
                    check(getSharedPreferences(SOURCE_PREFS, MODE_PRIVATE).edit().putString("url", source).commit())
                    OpenCodeUpdateState.checked(source, feed)
                    outcome = "发布者签名有效：${feed.version}。请核对后下载。"
                } else {
                    val feed = checkNotNull(OpenCodeUpdateState.current.value.feed)
                    check(feed.identity == confirmed)
                    val directory = UpdateFiles.directory(File(cacheDir, "opencode-updates"))
                    val destination = File(directory, "${UUID.randomUUID()}.ocaupdate"); staged = destination
                    val candidate = network.download(feed, destination, verifier)
                    owner.ensureActive(); OpenCodeUpdateState.publishCandidate(this@OpenCodeFetchService, candidate); staged = null
                    outcome = "下载、发布信息和包体签名校验通过，请确认安装。"
                }
            } catch (_: CancellationException) { outcome = "下载已取消，原版本保留。" }
            catch (_: Exception) { /* Never log server URLs, query tokens or remote error bodies. */ }
            finally {
                runCatching { client?.close() }; client = null; staged?.delete()
                if (wake.isHeld) wake.release()
                withContext(Dispatchers.Main.immediate + NonCancellable) {
                    finalized.set(true); OpenCodeUpdateState.finished(outcome)
                    worker = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        worker = task
        task.invokeOnCompletion {
            if (finalized.compareAndSet(false, true)) android.os.Handler(mainLooper).post {
                if (worker === task) {
                    OpenCodeUpdateState.finished("下载已停止，原版本保留。")
                    worker = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        task.start(); return START_NOT_STICKY
    }
    private fun cancelWork() {
        worker?.cancel()
        val pending = client
        // Closing TLS can block; do not block the Android main thread or cancel its own cleanup task.
        CoroutineScope(Dispatchers.IO).launch { runCatching { pending?.close() } }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelWork(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { cancelWork(); scope.cancel(); super.onDestroy() }
    private fun notification(message: String): Notification {
        val cancel = PendingIntent.getService(this, 5, Intent(this, OpenCodeFetchService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 5, Intent(this, io.github.opencodeandroid.MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(nativeMessage("OpenCode 更新")).setContentText(nativeMessage(message)).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, nativeMessage("取消下载"), cancel).build()).build()
    }
    companion object {
        const val CHECK = "opencode.update.CHECK_SOURCE"
        const val DOWNLOAD = "opencode.update.DOWNLOAD"
        const val CANCEL = "opencode.update.CANCEL_DOWNLOAD"
        const val SOURCE_PREFS = "opencode-update-source"
        private const val CHANNEL = "opencode-downloads"
        private const val LIMIT = 30L * 60 * 1000
        fun source(context: Context) = context.getSharedPreferences(SOURCE_PREFS, MODE_PRIVATE).getString("url", "").orEmpty()
    }
}
