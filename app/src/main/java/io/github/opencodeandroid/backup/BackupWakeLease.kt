package io.github.opencodeandroid.backup

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/** User-started IO only; expires after a stall and is released while waiting for confirmation. */
internal class BackupWakeLease(context: Context) : AutoCloseable {
    private val wake = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:workstation-backup").apply { setReferenceCounted(false) }
    private var renewed = Long.MIN_VALUE
    private var closed = false
    init { touch() }
    @Synchronized fun touch() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (!wake.isHeld || now - renewed >= 30 * 60 * 1000L) {
            wake.acquire(45 * 60 * 1000L); renewed = now
        }
    }
    @Synchronized override fun close() { closed = true; if (wake.isHeld) wake.release() }
}
