package io.github.opencodeandroid.runtime

import java.io.File

class ProotCommand(private val paths: RuntimePaths, private val identity: Int = 0, directory: String = WorkspaceContext.DEFAULT_DIRECTORY) {
    private val directory = WorkspaceContext.validDirectory(directory)
    init { require(identity in setOf(0, 1000)) }
    fun arguments(command: List<String>, extraEnvironment: Map<String, String> = emptyMap()): List<String> {
        check(paths.installed) { "Install Debian first" }
        paths.prepare()
        val args = mutableListOf(paths.proot.path, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L")
        if (identity == 0) args += "-0" else args += listOf("-i", "$identity:$identity")
        args += listOf("--rootfs=${paths.rootfs.canonicalPath}", "--cwd=$directory")
        val binds = listOf("/dev" to "/dev", "/proc" to "/proc", "/sys" to "/sys", "/dev/urandom" to "/dev/random",
            "/proc/self/fd" to "/dev/fd", "/proc/self/fd/0" to "/dev/stdin", "/proc/self/fd/1" to "/dev/stdout", "/proc/self/fd/2" to "/dev/stderr",
            paths.workspace.path to "/workspace", paths.home.path to "/root", paths.state.path to "/opencode-state",
            paths.desktop.path to "/desktop-state", paths.config.path to "/android-runtime", paths.temporary.path to "/tmp", paths.shm.path to "/dev/shm")
        binds.forEach { (host, guest) -> args += "--bind=$host:$guest" }
        io.github.opencodeandroid.runtime.update.OpenCodeUpdates(paths).active()?.first?.let { args += "--bind=${it.path}:/usr/local/bin/opencode" }
        args += listOf("/usr/bin/env", "-i")
        val user = if (identity == 0) "root" else "developer"
        val environment = linkedMapOf("HOME" to "/root", "USER" to user, "LOGNAME" to user, "SHELL" to "/bin/bash",
            "PATH" to "/android-runtime/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "BROWSER" to "/android-runtime/bin/android-open", "LANG" to "C.UTF-8", "TERM" to "xterm-256color", "TMPDIR" to "/tmp",
            "XDG_DATA_HOME" to "/opencode-state/data", "XDG_CONFIG_HOME" to "/opencode-state/config", "XDG_STATE_HOME" to "/opencode-state/state",
            "XDG_CACHE_HOME" to "/opencode-state/cache", "DO_NOT_TRACK" to "1") + extraEnvironment
        args += environment.map { (key, value) -> require(key.matches(Regex("[A-Z_][A-Z0-9_]*")) && '\u0000' !in value); "$key=$value" }
        args += command
        return args
    }

    fun hostEnvironment(): Map<String, String> {
        val links = File(paths.rootfs, ".l2s").apply { mkdirs() }
        return mapOf("PROOT_LOADER" to paths.loader.path, "PROOT_TMP_DIR" to paths.temporary.path,
            "PROOT_L2S_DIR" to links.canonicalPath, "PATH" to "/system/bin", "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data")
    }

    fun process(command: List<String>, environment: Map<String, String> = emptyMap()): NativeProcess.Builder =
        NativeProcess.Builder(arguments(command, environment), hostEnvironment())
}
