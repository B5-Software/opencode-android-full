package io.github.opencodeandroid.runtime

import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Full official executable and APK signer; ONLY isolated state is switched or migrated. */
class UpdateRuntimeAcceptanceTest {
    @Test fun fullOfficialPairStartsThroughSelectedProotBindAndRollsBack() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeHostSignedUpdate") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val original = RuntimePaths(context); check(original.installed)
        val bundle = File(context.getExternalFilesDir(null), "updates/native-signed.ocaupdate"); check(bundle.isFile)
        val root = File(context.cacheDir, "update-runtime-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val paths = RuntimePaths(isolated).apply { prepare() }
        // The installed Debian is read through this test alias; never changed or deleted.
        Os.symlink(original.rootfs.path, paths.rootfs.path)
        File(paths.state, "preserve.txt").writeText("original state")
        File(paths.workspace, "project.txt").writeText("original project")
        try {
            val manager = OpenCodeUpdates(paths); val metadata = SignedOpenCodeUpdate(context).inspect(bundle)
            var session = ""
            OpenCodeUpdateProbe.run(paths, metadata, onReady = { client -> session = client.createSession("/workspace").substringAfterLast('/') })
            val database = File(paths.state, "data/opencode/opencode-dev.db")
            assertTrue("official dev daemon initialized its isolated SQLite database", database.isFile)
            val originalDatabase = database.readBytes()
            RuntimeCoordination.claimMaintenance().use { lease -> manager.install(bundle, metadata.manifestSha256, lease) { expected ->
                val active = manager.active(true)!!
                assertTrue(ProotCommand(paths).arguments(listOf("/usr/local/bin/opencode", "--version")).contains("--bind=${active.first.path}:/usr/local/bin/opencode"))
                OpenCodeUpdateProbe.run(paths, expected, onReady = { client -> assertEquals("/workspace", client.sessionDirectory(session)) })
            } }
            assertEquals(metadata, manager.active(true)!!.second); assertTrue(manager.hasRollback())
            val updatedProgram = manager.active()!!.first
            assertTrue(database.isFile)
            assertEquals("original project", File(paths.workspace, "project.txt").readText())
            RuntimeCoordination.claimMaintenance().use { manager.rollback(it) }
            val restoredProgram = manager.active(true)!!
            assertEquals(metadata.binarySha256, restoredProgram.second.binarySha256)
            assertNotEquals(updatedProgram, restoredProgram.first)
            assertEquals("original state", File(paths.state, "preserve.txt").readText())
            assertArrayEquals(originalDatabase, database.readBytes())
            OpenCodeUpdateProbe.run(paths, metadata, onReady = { client -> assertEquals("/workspace", client.sessionDirectory(session)) })
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nPASS actual APK-signed complete official pair: selected PRoot bind, real daemon/version/anonymous 401, existing isolated session through update and rollback, byte-exact database restoration and unchanged project. User database was never switched.\n") })
        } finally { UpdateTransactionTest.removeOwnedTestTree(context, root); bundle.delete() }
    }
}
