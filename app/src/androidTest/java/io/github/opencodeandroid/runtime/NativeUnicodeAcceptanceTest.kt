package io.github.opencodeandroid.runtime

import android.os.ParcelFileDescriptor
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import io.github.opencodeandroid.runtime.update.OpenCodeUpdateProbe
import io.github.opencodeandroid.runtime.update.PublishedOpenCode

class NativeUnicodeAcceptanceTest {
    private val text = "中文-📦-🧪-" + String(Character.toChars(0x10ffff))
    private fun output(arguments: List<String>, environment: Map<String, String>): String {
        val child = NativeProcess.Builder(arguments, environment).start()
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS))
            val value = child.inputStream.bufferedReader().use { it.readText() }; assertEquals(value, 0, child.exitValue()); return value
        } finally { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
    }
    @Test fun nativeProcessAndPtyPreserveSupplementaryArgumentsAndHostEnvironment() {
        val arguments = listOf("/system/bin/sh", "-c", "printf '%s|%s' \"\${1}\" \"\${UNICODE_HOST_VALUE}\"", "native-utf8", text)
        val environment = mapOf("PATH" to "/system/bin", "UNICODE_HOST_VALUE" to text)
        RuntimeCoordination.claimService()!!.use {
            assertEquals("$text|$text", output(arguments, environment))
            val child = NativeHost.spawnPty(arguments.toTypedArray(), environment.map { "${it.key}=${it.value}" }.toTypedArray(), 24, 80)
            val master = ParcelFileDescriptor.adoptFd(child[0]); val bytes = ByteArrayOutputStream()
            val reader = Thread {
                try { ParcelFileDescriptor.AutoCloseInputStream(master).use { source -> val buffer = ByteArray(4096); while (true) { val count = source.read(buffer); if (count < 0) break; bytes.write(buffer, 0, count) } } }
                catch (_: IOException) { /* EIO marks a closed Linux PTY. */ }
            }.apply { isDaemon = true; start() }
            val waiter = FutureTask { NativeHost.waitFor(child[1]) }; Thread(waiter).apply { isDaemon = true; start() }
            try {
                assertEquals(0, waiter.get(15, TimeUnit.SECONDS)); reader.join(2000)
                assertEquals("$text|$text", bytes.toString("UTF-8"))
            } finally { NativeHost.signalGroup(child[1], 9); master.close(); waiter.get(3, TimeUnit.SECONDS); reader.join(1000) }
        }
    }
    @Test fun realDebianCwdAndGuestArgumentsResolveTheSameEmojiProjectDirectoryAsNativeFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = RuntimePaths(context); check(installed.installed)
        val root = File(context.cacheDir, "update-unicode-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val bindings = mapOf("/workspace" to "workspace", "/root" to "home", "/opencode-state" to "state", "/desktop-state" to "desktop", "/android-runtime" to "config", "/tmp" to "tmp", "/dev/shm" to "shm")
        bindings.values.forEach { File(root, it).mkdir() }
        val project = File(root, "workspace/项目-📦").apply { check(mkdir()) }
        File(project, "原生-🧪.txt").writeText(text)
        val l2s = File(root, "l2s").apply { check(mkdir()) }
        val template = ProotCommand(installed, directory = "/workspace/项目-📦")
        val script = "test \"\${PWD}\" = '/workspace/项目-📦'; test \"\${1}\" = \"\${UNICODE_GUEST_VALUE}\"; cat '原生-🧪.txt'"
        val arguments = template.arguments(listOf("/bin/bash", "-euc", script, "check", text), mapOf("UNICODE_GUEST_VALUE" to text)).map { argument ->
            if (argument.startsWith("--bind=")) bindings[argument.substringAfterLast(':')]?.let { "--bind=${File(root, it).path}:${argument.substringAfterLast(':')}" } ?: argument else argument
        }.toMutableList()
        arguments.add(arguments.indexOf("/usr/bin/env"), "--bind=${l2s.path}:/.l2s")
        try {
            RuntimeCoordination.claimService()!!.use { assertEquals(text, output(arguments, template.hostEnvironment() + ("PROOT_L2S_DIR" to l2s.path))) }
        } finally { UpdateTransactionTest.removeOwnedTestTree(context, root) }
    }
    @Test fun embeddedNulAndUnpairedSurrogatesAreRefusedBeforeAnyChildIsSpawned() {
        for (invalid in listOf("bad\u0000argument", "\ud800", "\udc00")) {
            try { NativeHost.spawnProcess(arrayOf("/system/bin/sh", invalid), emptyArray()); fail("invalid process argument was accepted") } catch (_: IOException) { }
            try { NativeHost.spawnPty(arrayOf("/system/bin/sh", invalid), emptyArray(), 24, 80); fail("invalid PTY argument was accepted") } catch (_: IOException) { }
            try { NativeHost.spawnProcess(arrayOf("/system/bin/sh", "-c", "exit 0"), arrayOf("KEY=$invalid")); fail("invalid environment was accepted") } catch (_: IOException) { }
        }
    }
    @Test fun actualOfficialDaemonUsesTheSameUnicodeDirectoryInItsIsolatedSessionDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = RuntimePaths(context); check(installed.installed)
        val root = File(context.cacheDir, "update-unicode-daemon-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val paths = RuntimePaths(object : ContextWrapper(context) { override fun getFilesDir() = files }).apply { prepare() }
        android.system.Os.symlink(installed.rootfs.path, paths.rootfs.path)
        check(File(paths.workspace, "项目-📦").mkdir())
        val published = PublishedOpenCode(installed)
        val proof = checkNotNull(published.stored() ?: published.bundled())
        val expected = published.verify(File(installed.rootfs, "usr/local/bin/opencode"), proof)
        try {
            RuntimeCoordination.claimService()!!.use {
                OpenCodeUpdateProbe.run(paths, expected, onReady = { client ->
                    val session = client.createSession("/workspace/项目-📦").substringAfterLast('/')
                    assertEquals("/workspace/项目-📦", client.sessionDirectory(session))
                })
            }
        } finally { UpdateTransactionTest.removeOwnedTestTree(context, root) }
    }
}
