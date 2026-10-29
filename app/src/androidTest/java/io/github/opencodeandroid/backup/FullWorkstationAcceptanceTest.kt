package io.github.opencodeandroid.backup

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.*
import io.github.opencodeandroid.runtime.update.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Fresh APK assets and synthetic accounts ONLY. Never archives or swaps the normal workstation. */
class FullWorkstationAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun report(message: String) { instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\n$message\n") }) }
    private fun guest(paths: RuntimePaths, command: String): String {
        val child = ProotCommand(paths).process(listOf("/bin/bash", "-euc", command)).start()
        child.outputStream.close()
        val output = StringBuilder()
        val reader = Thread { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { synchronized(output) { if (output.length < 16384) output.appendLine(it) } } } }.apply { isDaemon = true; start() }
        try { check(child.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)); reader.join(2000); check(child.exitValue() == 0) { "Guest check failed: ${output.toString().take(1000)}" }; return output.toString() }
        finally { child.destroyForcibly(); child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); reader.join(1000) }
    }
    @Test fun fullDebianDeveloperToolsSessionsAndUserTreesSurviveEncryptedRestoreAndWholeEnvironmentUndo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeFullWorkstationBackup") == "true")
        val context = instrumentation.targetContext
        InstrumentationRegistry.getArguments().getString("cleanupFullBackupFixture")?.let { name ->
            check(name.matches(Regex("update-full-backup-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
            val previous = File(context.cacheDir, name)
            if (previous.exists()) UpdateTransactionTest.removeOwnedTestTree(context, previous)
        }
        val root = File(context.cacheDir, "update-full-backup-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val prefix = "full-backup-${UUID.randomUUID()}-"; val preferenceNames = mutableSetOf<String>()
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = files
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                preferenceNames += name; return context.getSharedPreferences(prefix + name, mode)
            }
        }
        val paths = RuntimePaths(isolated); val settings = PortableSettings(isolated)
        val password = "synthetic-only-full-backup-passphrase".toCharArray()
        try {
            report("Installing a fresh, isolated APK-pinned Debian/developer kit for full backup acceptance; normal rootfs/home/state untouched.")
            RootfsInstaller(isolated, paths).install { if (it.contains("entries") || it.contains("installed")) report(it) }
            check(paths.installed)
            val proof = checkNotNull(PublishedOpenCode(paths).stored()); val expected = PublishedOpenCode(paths).metadata(proof)
            val project = File(paths.workspace, "项目-📦").apply { check(mkdir()) }
            File(project, "note.txt").writeText("encrypted whole-workstation Unicode data")
            File(paths.workspace, "main.c").writeText("#include <stdio.h>\nint main(void){puts(\"PASS restored compiler\");return 0;}\n")
            val ssh = File(paths.home, ".ssh").apply { check(mkdir()) }
            val key = File(ssh, "synthetic-key").apply { writeText("SYNTHETIC TEST DATA, NOT A REAL PRIVATE KEY"); Os.chmod(path, 384) }
            File(paths.rootfs, "etc/full-backup-fixture").writeText("snapshot-rootfs")
            guest(paths, "command -v gcc g++ node python3 curl wget; printf linked > /workspace/left; ln /workspace/left /workspace/right; test \"\$(cat /workspace/right)\" = linked")
            var session = ""
            OpenCodeUpdateProbe.run(paths, expected, onReady = { session = it.createSession("/workspace").substringAfterLast('/') })
            val database = File(paths.state, "data/opencode/opencode-dev.db"); assertTrue(database.isFile)
            val dbHash = UpdateFiles.sha(database)
            val wal = File(database.path + "-wal"); val walHash = if (wal.isFile) UpdateFiles.sha(wal) else null
            isolated.getSharedPreferences("workspace-navigation", 0).edit().putFloat("orb-opacity", .45f).commit()
            val archive = File(root, "workstation.ocawork")
            RuntimeCoordination.claimMaintenance("isolated full backup acceptance").use { lease ->
                var last = 0L
                WorkstationBackupFiles.write(paths, archive, password, lease, settings.snapshot()) { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last > 20_000) { last = now; report("Encrypted isolated full filesystem bytes: $bytes") }
                }
            }
            report("Full encrypted backup bytes=${archive.length()}; source roots kept for subsequent restore and undo checks.")
            File(paths.workspace, "work-after-export").writeText("keep newer original work")
            key.writeText("NEWER SYNTHETIC KEY DATA")
            File(paths.rootfs, "etc/full-backup-fixture").writeText("newer-original-rootfs")
            isolated.getSharedPreferences("workspace-navigation", 0).edit().putFloat("orb-opacity", .8f).commit()
            var newerSession = ""
            OpenCodeUpdateProbe.run(paths, expected, onReady = { newerSession = it.createSession("/workspace").substringAfterLast('/') })
            val manager = WorkstationRestore(paths, settings); val stage = manager.createStage()
            RuntimeCoordination.claimMaintenance("isolated full restore acceptance").use { lease ->
                var last = 0L
                val descriptor = archive.inputStream().use { input -> WorkstationArchive.stage(input, password, stage) { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last > 20_000) { last = now; report("Authenticated staging isolated full filesystem bytes: $bytes") }
                } }
                val inspected = WorkstationValidation.inspect(paths, stage, descriptor)
                assertTrue(inspected.installed); assertEquals(expected.binarySha256, inspected.core!!.binarySha256)
                manager.apply(stage, descriptor, lease, { tree, metadata -> WorkstationValidation.inspect(paths, tree, metadata) }, {
                    assertEquals(dbHash, UpdateFiles.sha(database)); if (walHash != null) assertEquals(walHash, UpdateFiles.sha(wal))
                    assertEquals("snapshot-rootfs", File(paths.rootfs, "etc/full-backup-fixture").readText())
                    assertEquals("encrypted whole-workstation Unicode data", File(project, "note.txt").readText())
                    assertEquals("SYNTHETIC TEST DATA, NOT A REAL PRIVATE KEY", key.readText()); assertEquals(384, Os.lstat(key.path).st_mode and 511)
                    assertFalse(File(paths.workspace, "work-after-export").exists())
                    val checks = guest(paths, "gcc /workspace/main.c -o /tmp/full-backup-hello; /tmp/full-backup-hello; command -v g++ node python3 curl wget; test \"\$(cat /workspace/right)\" = linked; printf restored-link > /workspace/right; test \"\$(cat /workspace/left)\" = restored-link")
                    assertTrue(checks.contains("PASS restored compiler"))
                    OpenCodeUpdateProbe.run(paths, expected, onReady = { assertEquals("/workspace", it.sessionDirectory(session)) })
                })
                assertEquals(.45f, isolated.getSharedPreferences("workspace-navigation", 0).getFloat("orb-opacity", 0f), .00001f)
                assertEquals("keep newer original work", File(stage.parentFile, "previous/workspace/work-after-export").readText())
                File(paths.workspace, "work-after-restore").writeText("keep restored environment work too")
                manager.undo(lease) { tree, roots -> WorkstationValidation.environment(paths, tree, roots) }
                assertEquals("keep newer original work", File(paths.workspace, "work-after-export").readText())
                assertEquals("NEWER SYNTHETIC KEY DATA", key.readText())
                assertEquals("newer-original-rootfs", File(paths.rootfs, "etc/full-backup-fixture").readText())
                assertEquals(.8f, isolated.getSharedPreferences("workspace-navigation", 0).getFloat("orb-opacity", 0f), .00001f)
                assertEquals("keep restored environment work too", File(stage.parentFile, "after-restore/workspace/work-after-restore").readText())
                OpenCodeUpdateProbe.run(paths, expected, onReady = { assertEquals("/workspace", it.sessionDirectory(newerSession)); assertEquals("/workspace", it.sessionDirectory(session)) })
                report("PASS full isolated Debian/developer kit encrypted export → authenticated staging → transactional restore → real gcc, linked-file writes and existing official session → whole-environment undo with both sets of newer work retained. Entries=${descriptor.entries}, plaintext bytes=${descriptor.dataBytes}; normal user namespace untouched.")
            }
        } catch (failure: Throwable) { report("Isolated full backup failure: ${failure.javaClass.simpleName}: ${failure.message?.take(400)}"); throw failure }
        finally {
            password.fill('\u0000')
            for (name in preferenceNames) context.deleteSharedPreferences(prefix + name)
            report("Cleaning only the owned isolated full-backup test directory.")
            UpdateTransactionTest.removeOwnedTestTree(context, root)
        }
    }
}
