package io.github.opencodeandroid.backup

import android.content.Context

/** Instrumentation injects its own cache/prefs namespace. Never included in a release APK. */
internal object WorkstationBackupHarness {
    @Volatile var context: Context? = null
}
class WorkstationBackupHarnessService : WorkstationBackupService() {
    override fun runtimeContext(): Context = checkNotNull(WorkstationBackupHarness.context) { "Instrumentation namespace is not configured" }
}
