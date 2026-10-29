package io.github.opencodeandroid.runtime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class InstallerRecoveryTest {
    @Test fun interruptedSwitchRestoresBackupAndCompletedSwitchRetainsIt() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(base.cacheDir, "installer-recovery-${UUID.randomUUID()}").apply { mkdir() }
        val context = object : ContextWrapper(base) { override fun getFilesDir(): File = temporary }
        val paths = RuntimePaths(context)
        assertEquals(temporary.canonicalPath, paths.files.path)
        paths.prepare()
        try {
            val backup = File(paths.backups, "rootfs-1-${UUID.randomUUID()}").apply { mkdir() }
            File(backup, ".installed-pack").writeText("old")
            val project = File(paths.workspace, "project.txt").apply { writeText("retained") }
            val journal = File(paths.config, "rootfs-update.json")
            journal.writeText(JSONObject().put("backup", backup.name).toString())
            val installer = RootfsInstaller(context, paths)
            installer.recoverInterruptedUpdate(::println)
            assertEquals("old", File(paths.rootfs, ".installed-pack").readText())
            assertFalse(journal.exists())
            assertFalse(backup.exists())
            backup.mkdir(); File(backup, ".installed-pack").writeText("old")
            File(paths.rootfs, ".installed-pack").writeText("new")
            journal.writeText(JSONObject().put("backup", backup.name).toString())
            installer.recoverInterruptedUpdate(::println)
            assertEquals("new", File(paths.rootfs, ".installed-pack").readText())
            assertEquals("old", File(backup, ".installed-pack").readText())
            assertEquals("retained", project.readText())
            journal.writeText(JSONObject().put("backup", "rootfs-2-${UUID.randomUUID()}").put("previouslyInstalled", false).toString())
            val partialName = JSONObject(journal.readText()).getString("backup")
            val partial = File(paths.backups, partialName).apply { mkdir() }
            File(partial, "user-file.txt").writeText("preserved")
            java.nio.file.Files.walk(paths.rootfs.toPath()).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } }
            installer.recoverInterruptedUpdate(::println)
            assertEquals("preserved", File(paths.rootfs, "user-file.txt").readText())
            assertEquals("retained", project.readText())
            journal.writeText(JSONObject().put("backup", "../../workspace").toString())
            assertTrue(runCatching { installer.recoverInterruptedUpdate(::println) }.isFailure)
            assertEquals("retained", project.readText())
        } finally {
            java.nio.file.Files.walk(temporary.toPath()).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } }
        }
    }
}
