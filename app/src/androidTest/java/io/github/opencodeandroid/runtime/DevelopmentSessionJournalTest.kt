package io.github.opencodeandroid.runtime

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class DevelopmentSessionJournalTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: (RuntimePaths) -> Unit) {
        val root = File(context.cacheDir, "update-history-${UUID.randomUUID()}").apply { check(mkdir()) }
        val paths = RuntimePaths(object : ContextWrapper(context) { override fun getFilesDir() = root }).apply { prepare() }
        try { test(paths) } finally { UpdateTransactionTest.removeOwnedTestTree(context, root) }
    }
    private fun journal(paths: RuntimePaths, owner: String = UUID.randomUUID().toString(), fault: () -> Unit = {}) =
        DevelopmentSessionJournal(paths, owner, 123, context.packageName, { 3 }, fault)
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe journal operation accepted") } catch (_: Exception) { } }
    @Test fun deadOwnerMarksOpenTerminalsAndBlocksAutomaticRestart() = fixture { paths ->
        val old = journal(paths); old.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE)
        old.terminalOpened(1); old.terminalOpened(2)
        val fresh = journal(paths)
        val after = fresh.snapshot()
        assertTrue(after.needsRestart(DevelopmentKind.RUNTIME)); assertNull(after.runtime.active)
        assertEquals(setOf(1, 2), after.runtime.interrupted!!.terminals); assertEquals(3, after.runtime.interrupted.exitReason)
        rejected { fresh.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE, automatic = true) }
        assertTrue(fresh.snapshot().needsRestart(DevelopmentKind.RUNTIME))
        val restarted = fresh.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE)
        assertFalse(fresh.snapshot().needsRestart(DevelopmentKind.RUNTIME)); assertNotNull(fresh.snapshot().runtime.interrupted)
        fresh.finish(DevelopmentKind.RUNTIME, restarted, false)
        assertNull(journal(paths).snapshot().runtime.active)
        assertFalse(journal(paths).snapshot().needsRestart(DevelopmentKind.RUNTIME))
    }
    @Test fun cleanCompletionDoesNotDemandRecoveryAndLiveOwnerIsNotStolen() = fixture { paths ->
        val journal = journal(paths); val token = journal.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.VERIFY)
        assertEquals(token, journal.snapshot().runtime.active!!.id)
        rejected { journal.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE) }
        journal.finish(DevelopmentKind.RUNTIME, token, false)
        assertFalse(journal(paths).snapshot().needsRestart(DevelopmentKind.RUNTIME))
    }
    @Test fun delayedOldCompletionCannotCloseNewRunOrReusedTerminalId() = fixture { paths ->
        val owner = UUID.randomUUID().toString(); val first = journal(paths, owner)
        val old = first.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE); first.terminalOpened(1)
        first.finish(DevelopmentKind.RUNTIME, old, false)
        val fresh = first.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE); first.terminalOpened(1)
        first.terminalClosed(old, 1); first.finish(DevelopmentKind.RUNTIME, old, true)
        assertEquals(setOf(1), first.snapshot().runtime.active!!.terminals)
        assertEquals(fresh, first.snapshot().runtime.active!!.id)
        first.terminalClosed(fresh, 1); assertTrue(first.snapshot().runtime.active!!.terminals.isEmpty())
    }
    @Test fun runtimeAndDesktopHaveIndependentRecoveryAndFailureStates() = fixture { paths ->
        val old = journal(paths)
        old.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE)
        val desktop = old.begin(DevelopmentKind.DESKTOP, DevelopmentOperation.DESKTOP)
        old.finish(DevelopmentKind.DESKTOP, desktop, true)
        assertTrue(old.snapshot().desktop.interrupted!!.failed)
        val fresh = journal(paths); fresh.snapshot()
        fresh.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE)
        assertFalse(fresh.snapshot().needsRestart(DevelopmentKind.RUNTIME)); assertTrue(fresh.snapshot().needsRestart(DevelopmentKind.DESKTOP))
    }
    @Test fun legacyMarkersMigrateOnceAndMetadataContainsNoWorkspaceContent() = fixture { paths ->
        File(paths.config, "session-state").writeText("running")
        File(paths.desktop, "session-state").writeText("stopped")
        val journal = journal(paths); assertTrue(journal.snapshot().needsRestart(DevelopmentKind.RUNTIME))
        File(paths.config, "session-state").writeText("stopped")
        assertTrue(journal.snapshot().needsRestart(DevelopmentKind.RUNTIME))
        val token = journal.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE)
        File(paths.workspace, "secret.txt").writeText("not-recorded-private-content")
        journal.terminalOpened(7)
        val data = journal.file.readText(); assertFalse(data.contains("not-recorded")); assertFalse(data.contains(paths.workspace.path))
        journal.finish(DevelopmentKind.RUNTIME, token, false)
    }
    @Test fun corruptDepthAndNonRegularFilesFailBeforeIoAndResetPreservesBytes() = fixture { paths ->
        val journal = journal(paths); journal.snapshot(); val file = journal.file
        val malformed = "{".repeat(100) + "}".repeat(100); file.writeText(malformed)
        rejected { journal.snapshot() }
        RuntimeCoordination.claimMaintenance().use { journal.reset(it) }
        assertFalse(journal.snapshot().needsRestart(DevelopmentKind.RUNTIME))
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("development-sessions-corrupt-") && it.readText() == malformed })
        check(file.delete()); val target = File(paths.workspace, "keep.txt").apply { writeText("preserve") }
        android.system.Os.symlink(target.path, file.path)
        rejected { journal.snapshot() }; rejected { journal.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE) }
        assertEquals("preserve", target.readText()); check(file.delete())
        android.system.Os.mkfifo(file.path, 384); rejected { journal.snapshot() }; check(file.delete())
    }
    @Test fun failedPersistenceLeavesPriorRunIntactAndTerminalCountIsBounded() = fixture { paths ->
        val owner = UUID.randomUUID().toString(); val healthy = journal(paths, owner)
        val token = healthy.begin(DevelopmentKind.RUNTIME, DevelopmentOperation.OPENCODE)
        repeat(8) { healthy.terminalOpened(it + 1) }
        rejected { healthy.terminalOpened(9) }
        val bytes = healthy.file.readBytes()
        val broken = journal(paths, owner) { throw java.io.IOException("injected full disk") }
        rejected { broken.finish(DevelopmentKind.RUNTIME, token, false) }
        assertArrayEquals(bytes, healthy.file.readBytes())
        assertTrue(healthy.snapshot().needsRestart(DevelopmentKind.RUNTIME))
        assertEquals(8, healthy.snapshot().runtime.interrupted!!.terminals.size)
        assertTrue(journal(paths).snapshot().needsRestart(DevelopmentKind.RUNTIME))
    }
    @Test fun atomicBackupRecoveryAndAllResetVariantsArePreserved() = fixture { paths ->
        val journal = journal(paths); journal.snapshot()
        val file = journal.file; val backup = File(file.path + ".bak")
        check(file.renameTo(backup)); assertFalse(journal.snapshot().needsRestart(DevelopmentKind.RUNTIME)); assertTrue(file.isFile)
        backup.writeText("old backup"); File(file.path + ".new").writeText("uncommitted new")
        RuntimeCoordination.claimMaintenance().use { journal.reset(it) }
        val retained = file.parentFile!!.listFiles()!!.filter { it.name.startsWith("development-sessions-corrupt-") }
        assertEquals(3, retained.size); assertTrue(retained.any { it.readText() == "old backup" }); assertTrue(retained.any { it.readText() == "uncommitted new" })
    }
}
