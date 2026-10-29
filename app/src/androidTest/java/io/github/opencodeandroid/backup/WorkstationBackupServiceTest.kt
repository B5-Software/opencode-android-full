package io.github.opencodeandroid.backup

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import io.github.opencodeandroid.runtime.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/** Exercises the actual FGS/request/URI/confirmation path against synthetic files, never the user roots. */
class WorkstationBackupServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(timeout: Long = 120_000, predicate: () -> Boolean) {
        val limit = android.os.SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(android.os.SystemClock.elapsedRealtime() < limit) { "Backup service timeout: ${WorkstationBackupState.current.value}" }; Thread.sleep(50) }
    }
    @Test fun foregroundExportContentUriSavePasswordRefusalReviewCancelConfirmedRestoreAndUndoUseOnlyInjectedRoots() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeBackupServiceHarness") == "true")
        val context = instrumentation.targetContext; val originalStatus = WorkstationBackupState.current.value
        val id = UUID.randomUUID().toString(); val prefix = "backup-fgs-$id-"; val names = mutableSetOf<String>()
        val root = File(context.cacheDir, "update-backup-service-$id").canonicalFile.apply { check(mkdir()) }
        val shared = File(context.cacheDir, "shared-files").apply { mkdir() }
        val documents = File(shared, "backup-fgs-$id").apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        var leakedIntentSecret = false
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = files
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences { names += name; return context.getSharedPreferences(prefix + name, mode) }
            override fun startForegroundService(service: Intent): ComponentName? {
                if (service.extras?.keySet() != setOf("requestId")) leakedIntentSecret = true
                return context.startForegroundService(Intent(service).setClass(context, WorkstationBackupHarnessService::class.java))
            }
        }
        WorkstationBackupHarness.context = isolated
        val paths = RuntimePaths(isolated).apply { prepare() }
        File(paths.workspace, "payload.bin").writeBytes(ByteArray(2 * 1024 * 1024 + 11).also(SecureRandom()::nextBytes))
        File(paths.workspace, "message").writeText("snapshot data")
        val originalHash = io.github.opencodeandroid.runtime.update.UpdateFiles.sha(File(paths.workspace, "payload.bin"))
        val password = "synthetic-native-service-passphrase"
        fun command(action: String, uri: android.net.Uri? = null) { context.startForegroundService(Intent(context, WorkstationBackupHarnessService::class.java).setAction(action).setData(uri)) }
        fun settled() = !WorkstationBackupState.current.value.busy && !RuntimeCoordination.maintenanceActive
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                WorkstationBackupState.submit(isolated, false, null, password.toCharArray())
                await { settled() && WorkstationBackupState.current.value.export != null }
                val info = checkNotNull(WorkstationExports(paths).ready()); assertTrue(info.bytes > 2 * 1024 * 1024)
                assertFalse(leakedIntentSecret)
                val saved = File(documents, "saved.ocawork").apply { check(createNewFile()) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", saved)
                command(WorkstationBackupService.SAVE, uri)
                await { settled() && saved.length() == info.bytes && WorkstationBackupState.current.value.message.contains("已保存") }
                assertEquals(io.github.opencodeandroid.runtime.update.UpdateFiles.sha(WorkstationExports(paths).file(info)), io.github.opencodeandroid.runtime.update.UpdateFiles.sha(saved))
                File(paths.workspace, "message").writeText("newer source data")
                WorkstationBackupState.submit(isolated, true, uri, "wrong-password".toCharArray())
                await { settled() && WorkstationBackupState.current.value.review == null }
                assertEquals("newer source data", File(paths.workspace, "message").readText())
                WorkstationBackupState.submit(isolated, true, uri, password.toCharArray())
                await { WorkstationBackupState.current.value.review != null && !WorkstationBackupState.current.value.busy }
                assertTrue(RuntimeCoordination.maintenanceActive)
                assertEquals("newer source data", File(paths.workspace, "message").readText())
                command(WorkstationBackupService.CANCEL)
                await { settled() && WorkstationBackupState.current.value.review == null }
                assertEquals("newer source data", File(paths.workspace, "message").readText())
                WorkstationBackupState.submit(isolated, true, uri, password.toCharArray())
                await { WorkstationBackupState.current.value.review != null && !WorkstationBackupState.current.value.busy }
                command(WorkstationBackupService.APPLY)
                await { settled() && WorkstationBackupState.current.value.restartRequired }
                assertEquals("snapshot data", File(paths.workspace, "message").readText())
                assertEquals(originalHash, io.github.opencodeandroid.runtime.update.UpdateFiles.sha(File(paths.workspace, "payload.bin")))
                File(paths.workspace, "after-restore").writeText("retain this too")
                WorkstationBackupState.acknowledgeRestart(); command(WorkstationBackupService.UNDO)
                await { settled() && WorkstationBackupState.current.value.restartRequired }
                assertEquals("newer source data", File(paths.workspace, "message").readText())
                assertFalse(leakedIntentSecret)
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nPASS actual native FGS: ephemeral request ID without password in Intent, encrypted export, content-URI save with byte identity, wrong password refusal, review before mutation, cancel, confirmed whole restore and undo; injected synthetic roots only.\n") })
            }
        } finally {
            context.stopService(Intent(context, WorkstationBackupHarnessService::class.java))
            await { !RuntimeCoordination.maintenanceActive }
            WorkstationBackupHarness.context = null
            WorkstationBackupState.change { originalStatus }; RuntimeState.refreshInstalled(RuntimePaths(context).installed)
            for (name in names) context.deleteSharedPreferences(prefix + name)
            check(documents.canonicalFile.parentFile == shared.canonicalFile && documents.name == "backup-fgs-$id")
            ArchiveTraversal.remove(documents.toPath()); UpdateTransactionTest.removeOwnedTestTree(context, root)
        }
    }
}
