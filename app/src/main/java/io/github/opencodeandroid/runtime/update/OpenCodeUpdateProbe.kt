package io.github.opencodeandroid.runtime.update

import android.os.SystemClock
import android.system.Os
import io.github.opencodeandroid.runtime.LocalOpenCode
import io.github.opencodeandroid.runtime.ProotCommand
import io.github.opencodeandroid.runtime.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real selected CLI, isolated loopback port, copied state. No browser is paired during a trial. */
internal object OpenCodeUpdateProbe {
    fun run(paths: RuntimePaths, expected: VerifiedOpenCodeUpdate, checkpoint: () -> Unit = {}, onReady: (LocalOpenCode) -> Unit = {}) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val password = UUID.randomUUID().toString()
        val config = File(paths.state, "config/opencode/service-dev.json")
        check(config.parentFile!!.isDirectory || config.parentFile!!.mkdirs())
        check(!java.nio.file.Files.isSymbolicLink(config.toPath()))
        if (UpdateFiles.exists(config)) { UpdateFiles.regular(config); check(config.delete()) }
        UpdateFiles.bytes(config, JSONObject().put("hostname", "127.0.0.1").put("port", port).put("password", password).toString().toByteArray())
        var child: io.github.opencodeandroid.runtime.NativeProcess? = null
        var reader: Thread? = null
        try {
            val process = ProotCommand(paths).process(listOf("/usr/local/bin/opencode", "serve", "--service", "--hostname", "127.0.0.1", "--port", port.toString()), mapOf("OPENCODE_DISABLE_AUTOUPDATE" to "1")).start()
            child = process; process.outputStream.close()
            reader = Thread({ runCatching { process.inputStream.use { input -> val bytes = ByteArray(8192); while (input.read(bytes) >= 0) { } } } }, "update-health-output").apply { isDaemon = true; start() }
            val local = LocalOpenCode(password, port); val deadline = SystemClock.elapsedRealtime() + 90_000
            while (process.isAlive && SystemClock.elapsedRealtime() < deadline) {
                checkpoint()
                if (runCatching { local.ready(expected.version) }.getOrDefault(false)) { onReady(local); return }
                Thread.sleep(150)
            }
            error("新版本未通过本地服务启动、版本和认证检查；将恢复原版本")
        } finally {
            child?.destroyForcibly()
            check(child?.waitFor(5, TimeUnit.SECONDS) != false) { "新版本试运行仍在退出，请等待恢复后重试" }
            reader?.join(1000)
            config.delete()
        }
    }
}
