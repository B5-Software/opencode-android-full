package io.github.opencodeandroid.runtime

import android.os.ParcelFileDescriptor
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Child handles originate only from JNI fork/exec of the APK's nativeLibraryDir. */
class NativeProcess private constructor(arguments: List<String>, environment: Map<String, String>) {
    private val child = NativeHost.spawnProcess(arguments.toTypedArray(), environment.map { "${it.key}=${it.value}" }.toTypedArray())
    val pid = child[1]
    val inputStream = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[0]))
    // Non-interactive processes receive /dev/null. PTY sessions have a separate input channel.
    val outputStream = object : OutputStream() { override fun write(value: Int) { throw IOException("Use a PTY for interactive input") } }
    private val exited = CountDownLatch(1)
    @Volatile private var status: Int? = null
    val isAlive get() = status == null

    init {
        Thread({
            status = NativeHost.waitFor(pid)
            exited.countDown()
        }, "guest-wait-$pid").apply { isDaemon = true; start() }
    }

    fun waitFor(timeout: Long, unit: TimeUnit) = exited.await(timeout, unit)
    fun exitValue(): Int = status ?: throw IllegalThreadStateException("Guest is still running")
    fun destroyForcibly() { if (isAlive) NativeHost.signalProcess(pid, 9) }

    class Builder(private val arguments: List<String>, private val environment: Map<String, String>) {
        fun start() = NativeProcess(arguments, environment)
    }
}
