package io.github.opencodeandroid.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.RuntimeCoordination
import io.github.opencodeandroid.runtime.RuntimePaths
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

class WorkstationRestoreTest {
    private class MemorySettings : RestoreSettings {
        var value = JSONObject().put("value", "old")
        override fun snapshot() = JSONObject(value.toString())
        override fun validate(value: JSONObject) { check(value.getString("value").length <= 30) }
        override fun restore(value: JSONObject) { validate(value); this.value = JSONObject(value.toString()) }
    }
    private class Fixture(context: Context, originalRootfs: Boolean = true) : AutoCloseable {
        val directory = File(context.cacheDir, "update-restore-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val paths = RuntimePaths(object : ContextWrapper(context) { override fun getFilesDir() = File(directory, "files").apply { mkdir() } })
        val state = MemorySettings()
        init {
            paths.prepare(); if (originalRootfs) check(paths.rootfs.mkdir())
            for (name in WorkstationRoots.names) if (File(paths.files, name).isDirectory) File(paths.files, "$name/marker").writeText("old:$name")
        }
        fun engine(failure: String? = null) = WorkstationRestore(paths, state) { if (it == failure) throw IOException("power loss at $it") }
        fun stage(label: String = "new", rootfs: Boolean = true): Pair<File, StagedWorkstation> {
            val tree = engine().createStage()
            val names = WorkstationRoots.names.filter { rootfs || it != "rootfs" }.toSet()
            for (name in names) { val root = File(tree, name); check(root.mkdir()); File(root, "marker").writeText("$label:$name") }
            return tree to StagedWorkstation(JSONObject().put("settings", JSONObject().put("value", label)), names, names.size * 2L, 50L)
        }
        fun assertLive(label: String, rootfs: Boolean = true) {
            for (name in WorkstationRoots.names) {
                if (name == "rootfs" && !rootfs) assertFalse(paths.rootfs.exists())
                else assertEquals("$label:$name", File(paths.files, "$name/marker").readText())
            }
            assertEquals(label, state.value.getString("value"))
        }
        override fun close() {
            check(directory.parentFile == paths.applicationContext.cacheDir.canonicalFile && directory.name.startsWith("update-restore-"))
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult { if (failure != null) throw failure; Files.delete(dir); return FileVisitResult.CONTINUE }
            })
        }
    }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun mustFail(block: () -> Unit) { try { block(); fail("Expected injected or preflight failure") } catch (expected: IOException) {} catch (expected: IllegalStateException) {} }

    @Test fun everyPreparedBoundaryRestoresAllSevenRootsAndSettingsWithoutFabricatingDirectories() {
        val boundaries = listOf("journal") + WorkstationRoots.names.flatMap { listOf("saved:$it", "installed:$it") } + listOf("settings", "verified")
        for (boundary in boundaries) Fixture(context).use { f ->
            val (stage, descriptor) = f.stage()
            RuntimeCoordination.claimMaintenance().use { lease ->
                mustFail { f.engine(boundary).apply(stage, descriptor, lease, { _, _ -> }, {}) }
                val absent = WorkstationRoots.names.filter { !File(f.paths.files, it).exists() }
                f.paths.prepare()
                assertEquals("prepare fabricated a directory at $boundary", absent, WorkstationRoots.names.filter { !File(f.paths.files, it).exists() })
                f.engine().recover(lease); f.engine().recover(lease)
                f.assertLive("old"); assertFalse(f.engine().pending()); assertFalse(f.engine().canUndo())
            }
        }
    }

    @Test fun committedBoundariesFinishTheNewEnvironmentAndRetainThePreviousEnvironment() {
        for (boundary in listOf("committed", "before-journal-clear")) Fixture(context).use { f ->
            val (stage, descriptor) = f.stage()
            RuntimeCoordination.claimMaintenance().use { lease ->
                mustFail { f.engine(boundary).apply(stage, descriptor, lease, { _, _ -> }, {}) }
                f.engine().recover(lease); f.engine().recover(lease); f.assertLive("new"); assertTrue(f.engine().canUndo())
                for (name in WorkstationRoots.names) assertEquals("old:$name", File(stage.parentFile, "previous/$name/marker").readText())
            }
        }
    }

    @Test fun failedProbeReturnsTheCompleteOldEnvironmentAndRetainsNewPartialWork() {
        Fixture(context).use { f ->
            val (stage, descriptor) = f.stage()
            RuntimeCoordination.claimMaintenance().use { lease ->
                mustFail { f.engine().apply(stage, descriptor, lease, { _, _ -> }, {
                    File(f.paths.workspace, "partial-agent-work").writeText("preserved")
                    throw IOException("health probe failed")
                }) }
                f.engine().recover(lease); f.assertLive("old")
                assertEquals("preserved", File(stage.parentFile, "failed/workspace/partial-agent-work").readText())
            }
        }
    }

    @Test fun recoveryCanItselfBeInterruptedAfterRemovingOrRestoringEveryRoot() {
        val boundaries = WorkstationRoots.names.flatMap { listOf("rollback-removed:$it", "rollback-restored:$it") } + "rollback-settings"
        for (boundary in boundaries) Fixture(context).use { f ->
            val (stage, descriptor) = f.stage()
            RuntimeCoordination.claimMaintenance().use { lease ->
                mustFail { f.engine("verified").apply(stage, descriptor, lease, { _, _ -> }, {}) }
                mustFail { f.engine(boundary).recover(lease) }
                f.paths.prepare(); f.engine().recover(lease); f.assertLive("old"); assertFalse(f.engine().pending())
            }
        }
    }

    @Test fun approvedUndoCompletesAfterEveryBoundaryAndKeepsLaterUserWork() {
        val boundaries = listOf("undo-journal") + WorkstationRoots.names.flatMap { listOf("undo-saved:$it", "undo-restored:$it") } + listOf("undo-settings", "undo-committed")
        for (boundary in boundaries) Fixture(context).use { f ->
            val (stage, descriptor) = f.stage()
            RuntimeCoordination.claimMaintenance().use { lease ->
                f.engine().apply(stage, descriptor, lease, { _, _ -> }, {})
                File(f.paths.workspace, "later-user-work").writeText("keep this too")
                mustFail { f.engine(boundary).undo(lease) { _, _ -> } }
                f.paths.prepare(); f.engine().recover(lease); f.engine().recover(lease)
                f.assertLive("old"); assertFalse(f.engine().canUndo())
                assertEquals("keep this too", File(stage.parentFile, "after-restore/workspace/later-user-work").readText())
            }
        }
    }

    @Test fun absentRootfsIsHandledOnEitherSideWithoutCreatingAnUnrequestedEnvironment() {
        for ((old, new) in listOf(false to true, true to false, false to false)) Fixture(context, old).use { f ->
            val (stage, descriptor) = f.stage(rootfs = new)
            RuntimeCoordination.claimMaintenance().use { lease ->
                f.engine().apply(stage, descriptor, lease, { _, _ -> }, {}); f.assertLive("new", new)
                f.engine().undo(lease) { _, _ -> }; f.assertLive("old", old)
            }
        }
    }

    @Test fun multipleRestoresUndoInOrderAndPreserveBothNewerEnvironments() {
        Fixture(context).use { f ->
            val first = f.stage("first")
            RuntimeCoordination.claimMaintenance().use { lease -> f.engine().apply(first.first, first.second, lease, { _, _ -> }, {}) }
            val second = f.stage("second")
            RuntimeCoordination.claimMaintenance().use { lease ->
                f.engine().apply(second.first, second.second, lease, { _, _ -> }, {}); f.assertLive("second")
                f.engine().undo(lease) { _, _ -> }; f.assertLive("first"); assertTrue(f.engine().canUndo())
                f.engine().undo(lease) { _, _ -> }; f.assertLive("old"); assertFalse(f.engine().canUndo())
            }
            assertEquals("second:workspace", File(second.first.parentFile, "after-restore/workspace/marker").readText())
            assertEquals("first:workspace", File(first.first.parentFile, "after-restore/workspace/marker").readText())
        }
    }

    @Test fun preflightRefusalsDoNotPublishAJournalOrReplaceLiveFilesAndUnapprovedStagesCanBeDiscarded() {
        Fixture(context).use { f ->
            val (stage, descriptor) = f.stage()
            val outside = File(f.directory, "outside").apply { check(mkdir()) }; File(outside, "sentinel").writeText("untouched")
            android.system.Os.symlink(outside.path, File(stage, "workspace/external").path)
            RuntimeCoordination.claimMaintenance().use { lease ->
                mustFail { f.engine().apply(stage, descriptor, lease, { _, _ -> error("wrong publisher/ABI") }, {}) }
                f.assertLive("old"); assertFalse(f.engine().pending())
            }
            f.engine().discardStage(stage); assertEquals("untouched", File(outside, "sentinel").readText())
            assertFalse(stage.parentFile!!.exists())
        }
    }
}
