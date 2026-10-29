package io.github.opencodeandroid.backup

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.files.ProjectFilesViewModel
import io.github.opencodeandroid.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class FileMaintenanceCoordinationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun fixture(action: (ProjectFilesViewModel, ViewModelStore, RuntimePaths) -> Unit) {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "update-file-maintenance-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val application = object : Application() {
            override fun getFilesDir() = files
            override fun getApplicationInfo() = context.applicationInfo
            override fun getApplicationContext(): Context = context.applicationContext
        }
        val paths = RuntimePaths(application).apply { prepare() }
        File(paths.workspace, "fixture.txt").writeText("owned fixture")
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ProjectFilesViewModel(application) as T
        }
        val viewModel = ViewModelProvider(store, factory)[ProjectFilesViewModel::class.java]
        try { action(viewModel, store, paths) }
        finally { instrumentation.runOnMainSync { store.clear() }; instrumentation.waitForIdleSync(); UpdateTransactionTest.removeOwnedTestTree(context, root) }
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("maintenance raced a file task") } catch (expected: IllegalStateException) {} }
    @Test fun fileTaskClaimsOwnershipBeforeLaunchingAndReleasesItAfterCompletionOrAnAlreadyClearedScope() = fixture { model, store, _ ->
        instrumentation.runOnMainSync {
            model.refresh(); assertTrue(model.state.value.busy)
            rejected { RuntimeCoordination.claimMaintenance() }
        }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (model.state.value.busy) { check(android.os.SystemClock.elapsedRealtime() < deadline); Thread.sleep(20) }
        instrumentation.waitForIdleSync()
        RuntimeCoordination.claimMaintenance().close()
        instrumentation.runOnMainSync { store.clear(); model.refresh() }
        // A coroutine that never entered its body must still release its synchronous owner.
        RuntimeCoordination.claimMaintenance().close()
    }
    @Test fun maintenanceAndPendingRestoreBlockFileAccessBeforeTheRepositoryIsOpened() = fixture { model, _, paths ->
        RuntimeCoordination.claimMaintenance().use {
            instrumentation.runOnMainSync { model.refresh() }
            assertFalse(model.state.value.busy); assertTrue(model.state.value.error!!.contains("维护"))
        }
        File(paths.files, "maintenance").mkdir()
        File(paths.files, "maintenance/workstation-restore.json").writeText("{}")
        instrumentation.runOnMainSync { model.refresh() }
        assertFalse(model.state.value.busy); assertTrue(model.state.value.error!!.contains("恢复"))
        assertEquals("owned fixture", File(paths.workspace, "fixture.txt").readText())
    }
    @Test fun anotherEditorsUnsavedDraftIsVisibleAndAnOldEditorCannotWriteAfterAnotherWindowAcknowledgesRestore() = fixture { model, _, paths ->
        fun awaitFileTask() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (model.state.value.busy) { check(android.os.SystemClock.elapsedRealtime() < deadline); Thread.sleep(20) }
            instrumentation.waitForIdleSync()
        }
        val before = WorkstationBackupState.current.value
        try {
            instrumentation.runOnMainSync { model.refresh() }; awaitFileTask()
            instrumentation.runOnMainSync { model.open(model.state.value.entries.single()) }; awaitFileTask()
            instrumentation.runOnMainSync { model.edit("unsaved other-window draft") }
            assertTrue(ProjectFilesViewModel.hasUnsavedEditors())
            RuntimeCoordination.claimMaintenance().use {
                instrumentation.runOnMainSync { model.edit("late input during maintenance") }
                assertEquals("unsaved other-window draft", model.state.value.draft)
            }
            WorkstationBackupState.environmentChanged("test-only other window restore")
            WorkstationBackupState.acknowledgeRestart()
            instrumentation.runOnMainSync { model.save() }
            assertEquals("owned fixture", File(paths.workspace, "fixture.txt").readText())
            assertTrue(model.state.value.error!!.contains("重开")); assertTrue(model.state.value.dirty)
        } finally { WorkstationBackupState.change { before } }
    }
}
