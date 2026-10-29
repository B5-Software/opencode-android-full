package io.github.opencodeandroid.runtime

import io.github.opencodeandroid.backup.PortableSettings
import io.github.opencodeandroid.backup.WorkstationRestore
import io.github.opencodeandroid.backup.WorkstationRoots
import io.github.opencodeandroid.backup.RestoreSettings
import io.github.opencodeandroid.runtime.update.OpenCodeUpdates

internal object RuntimeRecovery {
    fun pending(paths: RuntimePaths) = WorkstationRoots.pending(paths) ||
        RootfsInstaller(paths.applicationContext, paths).recoveryPending() || OpenCodeUpdates(paths).recoveryPending()
    fun recover(paths: RuntimePaths, lease: RuntimeCoordination.MaintenanceLease, log: (String) -> Unit,
                settings: RestoreSettings = PortableSettings(paths.applicationContext)) {
        lease.checkCurrent()
        val workstationPending = WorkstationRoots.pending(paths)
        WorkstationRestore(paths, settings).recover(lease)
        if (workstationPending) io.github.opencodeandroid.backup.WorkstationBackupState.environmentChanged("已完成中断操作的恢复，请重开工作区。")
        lease.checkCurrent(); paths.prepare()
        RootfsInstaller(paths.applicationContext, paths).recoverInterruptedUpdate(log)
        OpenCodeUpdates(paths).recover(lease)
        WorkspaceContext.reload(paths.applicationContext)
    }
}
