package io.github.opencodeandroid.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files

/** Keep libc DNS aligned with Android's current network without embedding public resolvers. */
object GuestNetwork {
    fun configure(context: Context, paths: RuntimePaths): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
        if (servers.isEmpty()) return false
        val directory = File(paths.rootfs, "etc")
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val file = AtomicFile(File(directory, "resolv.conf"))
        val output = file.startWrite()
        try {
            output.write(servers.joinToString("\n", postfix = "\n") { "nameserver ${it.hostAddress}" }.toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) { file.failWrite(output); throw failure }
        return true
    }
}
