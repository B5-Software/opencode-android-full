package io.github.opencodeandroid.backup

import io.github.opencodeandroid.runtime.RuntimePaths
import java.io.File

internal object WorkstationRoots {
    val names = listOf("rootfs", "workspace", "home", "opencode-state", "desktop-state", "opencode-runtimes", "runtime-config")
    fun pending(files: File): Boolean {
        val journal = File(files, "maintenance/workstation-restore.json")
        return journal.exists() || File(journal.path + ".bak").exists()
    }
    fun pending(paths: RuntimePaths) = pending(paths.files)
}
