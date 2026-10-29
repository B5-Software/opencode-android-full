package io.github.opencodeandroid.runtime

import android.content.Context
import java.io.File

class RuntimePaths(context: Context) {
    internal val applicationContext = context.applicationContext
    val files = context.filesDir.canonicalFile
    val rootfs = File(files, "rootfs")
    val workspace = File(files, "workspace")
    val home = File(files, "home")
    val state = File(files, "opencode-state")
    val desktop = File(files, "desktop-state")
    val config = File(files, "runtime-config")
    val temporary = File(files, "tmp")
    val shm = File(files, "shm")
    val backups = File(files, "backups")
    val openCodeVersions = File(files, "opencode-runtimes")
    private val native = File(context.applicationInfo.nativeLibraryDir).canonicalFile
    val proot get() = nativeExecutable("libproot.so")
    val loader get() = nativeExecutable("libproot-loader.so")
    val installed get() = File(rootfs, ".installed-pack").isFile

    fun prepare() {
        listOf(workspace, home, state, desktop, config, temporary, shm, backups, openCodeVersions).forEach {
            check(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "Managed runtime directory cannot be a symbolic link: ${it.name}" }
            // A journal may own a temporarily absent state tree. Do not manufacture a replacement
            // before rename-only recovery has restored the original directory.
            val updateJournal = File(config, "opencode-update-journal.json")
            if (it.name in io.github.opencodeandroid.backup.WorkstationRoots.names && !it.exists() &&
                io.github.opencodeandroid.backup.WorkstationRoots.pending(files)) return@forEach
            if (it == state && !it.exists() && (updateJournal.exists() || File(updateJournal.path + ".bak").exists())) return@forEach
            check(it.isDirectory || it.mkdirs()) { "Cannot create ${it.name}" }
        }
    }

    private fun nativeExecutable(name: String): File {
        val file = File(native, name).canonicalFile
        check(file.parentFile == native && file.isFile && file.canExecute()) {
            "Missing executable APK component: $name. Build native-host/build.sh before packaging."
        }
        return file
    }
}
