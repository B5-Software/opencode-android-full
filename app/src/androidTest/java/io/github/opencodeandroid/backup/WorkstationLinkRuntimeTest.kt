package io.github.opencodeandroid.backup

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class WorkstationLinkRuntimeTest {
    @Test fun guestHardlinksSurviveEncryptedBackupRelocationWritesAndUnlink() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeLinuxArchiveLinks") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val installed = RuntimePaths(context); check(installed.installed)
        val root = File(context.cacheDir, "update-archive-linux-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = File(root, "source").apply { check(mkdir()) }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val source = RuntimePaths(wrapped).apply { prepare(); rootfs.mkdir(); File(rootfs, ".l2s").mkdir() }
        fun guest(directory: File, command: List<String>): String {
            val tree = directory.canonicalFile
            val template = ProotCommand(installed)
            val bindings = mapOf("/workspace" to "workspace", "/root" to "home", "/opencode-state" to "opencode-state", "/desktop-state" to "desktop-state", "/android-runtime" to "runtime-config", "/tmp" to "tmp", "/dev/shm" to "shm")
            bindings.values.forEach { File(tree, it).mkdirs() }
            val l2s = File(tree, "rootfs/.l2s").apply { mkdirs() }
            val arguments = template.arguments(command).map { argument ->
                if (argument.startsWith("--bind=")) bindings[argument.substringAfterLast(':')]?.let { "--bind=${File(tree, it).path}:${argument.substringAfterLast(':')}" } ?: argument else argument
            }.toMutableList()
            arguments.add(arguments.indexOf("/usr/bin/env"), "--bind=${l2s.path}:/.l2s")
            RuntimeCoordination.claimService()!!.use {
                val child = NativeProcess.Builder(arguments, template.hostEnvironment() + ("PROOT_L2S_DIR" to l2s.canonicalPath)).start()
                try {
                    check(child.waitFor(12, TimeUnit.SECONDS)) { "Fixture Linux command timed out" }
                    val output = child.inputStream.bufferedReader().use { it.readText() }
                    check(child.exitValue() == 0) { "Fixture Linux command failed: $output" }; return output.trim()
                } finally { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }
            }
        }
        val password = "native Linux fixture backup password".toCharArray()
        try {
            File(source.workspace, "left").writeText("original")
            guest(source.files, listOf("/usr/bin/ln", "/workspace/left", "/workspace/right"))
            assertEquals("original", guest(source.files, listOf("/bin/cat", "/workspace/right")))
            guest(source.files, listOf("/bin/bash", "-c", "printf original > /workspace/right"))
            val initial = guest(source.files, listOf("/usr/bin/stat", "-c", "%h:%i", "/workspace/left", "/workspace/right")).lines()
            assertEquals(2, initial.size); assertEquals(initial[0], initial[1]); assertTrue(initial[0].startsWith("2:"))
            val backup = ByteArrayOutputStream()
            RuntimeCoordination.claimMaintenance().use { WorkstationArchive.write(source, backup, password, it) }
            val staged = File(root, "staged").apply { check(mkdir()) }
            val descriptor = WorkstationArchive.stage(ByteArrayInputStream(backup.toByteArray()), password, staged)
            assertTrue("relocated hard-link chain resolves in host staging", File(staged, "workspace/right").exists())
            val restored = File(root, "restored").apply { check(mkdir()) }
            ArchiveLinks.rebase(staged, restored, descriptor.roots)
            for (name in descriptor.roots) android.system.Os.rename(File(staged, name).path, File(restored, name).path)
            val stat = guest(restored, listOf("/usr/bin/stat", "-c", "%h:%i", "/workspace/left", "/workspace/right")).lines()
            assertEquals(2, stat.size); assertEquals(stat[0], stat[1]); assertTrue(stat[0].startsWith("2:"))
            assertEquals("original", guest(restored, listOf("/bin/cat", "/workspace/right")))
            guest(restored, listOf("/bin/bash", "-c", "printf changed > /workspace/right"))
            assertEquals("changed", guest(restored, listOf("/bin/cat", "/workspace/left")))
            assertEquals("original", guest(source.files, listOf("/bin/cat", "/workspace/left")))
            guest(restored, listOf("/bin/rm", "/workspace/right"))
            assertEquals("1", guest(restored, listOf("/usr/bin/stat", "-c", "%h", "/workspace/left")))
            assertEquals("changed", guest(restored, listOf("/bin/cat", "/workspace/left")))
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nPASS real Debian ln/stat/write/unlink on relocated encrypted backup; virtual inode/link counts retained; original fixture isolated.\n") })
        } finally { password.fill('\u0000'); UpdateTransactionTest.removeOwnedTestTree(context, root) }
    }
}
