package io.github.opencodeandroid.runtime

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.UUID
import java.util.zip.*

/** Transaction fault injection in an owned namespace. Synthetic ELF bytes are never executed. */
class UpdateTransactionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private fun signatures() = SignedOpenCodeUpdate(listOf(key.public), 1)
    private class PowerLoss : Error()
    private fun rejected(action: () -> Unit) { try { action(); fail("Unsafe operation was accepted") } catch (_: IllegalStateException) { } }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(directory: File): File {
        val elf = ByteArray(16384)
        val header = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(127, 69, 76, 70, 2, 1, 1)); header.putShort(16, 3); header.putShort(18, 183)
        header.putLong(32, 64); header.putShort(52, 64); header.putShort(54, 56); header.putShort(56, 2)
        header.putInt(64, 1); header.putLong(96, elf.size.toLong()); header.putLong(104, elf.size.toLong()); header.putLong(112, 16384)
        val interpreter = "/lib/ld-linux-aarch64.so.1\u0000".toByteArray()
        header.putInt(120, 3); header.putLong(128, 256); header.putLong(152, interpreter.size.toLong()); interpreter.copyInto(elf, 256)
        val packed = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(elf) } }.toByteArray()
        val manifest = JSONObject().put("format", 1).put("kind", "opencode-pair").put("abi", "arm64-v8a")
            .put("repository", "https://github.com/anomalyco/opencode.git").put("version", "2.1.4-transaction-fixture").put("commit", "a".repeat(40)).put("minHostVersionCode", 1)
            .put("binary", JSONObject().put("size", elf.size).put("sha256", hash(elf)))
            .put("archive", JSONObject().put("size", packed.size).put("sha256", hash(packed))).toString().toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(manifest); sign() }
        return File(directory, "fixture.ocaupdate").also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in listOf("manifest.json" to manifest, "manifest.sig" to signed, "opencode.pack" to packed)) {
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size; crc = CRC32().apply { update(bytes) }.value }); zip.write(bytes); zip.closeEntry()
            }
        } }
    }
    private fun fixture(test: (RuntimePaths, File) -> Unit) {
        val root = File(context.cacheDir, "update-transaction-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = File(root, "files").apply { check(mkdir()) }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val paths = RuntimePaths(isolated).apply { prepare() }
        File(paths.rootfs, ".installed-pack").apply { parentFile!!.mkdirs(); writeText("test marker, not executable") }
        File(paths.rootfs, "usr/local/bin/opencode").apply { parentFile!!.mkdirs(); writeText("test baseline, never executed") }
        File(paths.workspace, "project.txt").writeText("user project")
        writeDatabase(paths.state, "before")
        try { test(paths, archive(root)) } finally { removeOwnedTestTree(context, root) }
    }
    private fun writeDatabase(state: File, text: String) {
        SQLiteDatabase.openOrCreateDatabase(File(state, "history.db"), null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS messages (text TEXT NOT NULL)")
            db.execSQL("DELETE FROM messages"); db.execSQL("INSERT INTO messages VALUES (?)", arrayOf(text))
        }
    }
    private fun readDatabase(state: File): String = SQLiteDatabase.openDatabase(File(state, "history.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT text FROM messages", null).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
    }
    private fun backups(paths: RuntimePaths) = paths.backups.listFiles()!!.single()
    @Test fun serviceClaimsAndMaintenanceExcludeEachOtherWithoutStaleRelease() {
        val service = RuntimeCoordination.claimService()!!
        rejected { RuntimeCoordination.claimMaintenance() }; service.close()
        val first = RuntimeCoordination.claimMaintenance(); assertNull(RuntimeCoordination.claimService())
        val resumed = first.resumeService(); first.close(); rejected { RuntimeCoordination.claimMaintenance() }; resumed.close()
        val second = RuntimeCoordination.claimMaintenance(); first.close(); assertNull(RuntimeCoordination.claimService()); second.checkCurrent(); second.close()
    }
    @Test fun successfulSwitchPreservesOriginalSqliteAndRollbackKeepsNewerState() = fixture { paths, bundle ->
        val manager = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }); val metadata = signatures().inspect(bundle)
        val original = File(paths.state, "history.db").readBytes()
        RuntimeCoordination.claimMaintenance().use { lease ->
            manager.install(bundle, metadata.manifestSha256, lease) {
                assertEquals("before", readDatabase(paths.state)); writeDatabase(paths.state, "after")
            }
        }
        assertEquals(metadata, manager.active(true)!!.second); assertEquals("after", readDatabase(paths.state)); assertTrue(manager.hasRollback())
        assertArrayEquals(original, File(backups(paths), "previous-state/history.db").readBytes())
        RuntimeCoordination.claimMaintenance().use { manager.rollback(it) }
        assertNull(manager.active()); assertFalse(manager.hasRollback()); assertEquals("before", readDatabase(paths.state))
        assertArrayEquals(original, File(paths.state, "history.db").readBytes())
        assertEquals("after", readDatabase(File(backups(paths), "after-update-state")))
        assertEquals("user project", File(paths.workspace, "project.txt").readText())
    }
    @Test fun failedHealthCheckRestoresOriginalAndRetainsFailedMigration() = fixture { paths, bundle ->
        val manager = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }); val metadata = signatures().inspect(bundle)
        RuntimeCoordination.claimMaintenance().use { lease ->
            rejected { manager.install(bundle, metadata.manifestSha256, lease) { writeDatabase(paths.state, "failed migration"); error("fixture failure") } }
        }
        assertFalse(manager.recoveryPending()); assertNull(manager.active()); assertEquals("before", readDatabase(paths.state))
        assertEquals("failed migration", readDatabase(File(backups(paths), "failed-state")))
    }
    @Test fun chainedUpdatesRejectDamagedPreviousProgramAndRollbackInOrder() = fixture { paths, bundle ->
        val manager = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }); val metadata = signatures().inspect(bundle)
        RuntimeCoordination.claimMaintenance().use { lease -> manager.install(bundle, metadata.manifestSha256, lease) { writeDatabase(paths.state, "one") } }
        val old = manager.active()!!.first; val bytes = old.readBytes()
        RuntimeCoordination.claimMaintenance().use { lease -> manager.install(bundle, metadata.manifestSha256, lease) { writeDatabase(paths.state, "two") } }
        Os.chmod(old.path, 384); old.appendBytes(byteArrayOf(1))
        RuntimeCoordination.claimMaintenance().use { lease -> rejected { manager.rollback(lease) } }
        assertEquals("two", readDatabase(paths.state)); assertFalse(manager.recoveryPending())
        old.writeBytes(bytes); Os.chmod(old.path, 320)
        RuntimeCoordination.claimMaintenance().use { manager.rollback(it) }
        assertEquals(old, manager.active(true)!!.first); assertEquals("one", readDatabase(paths.state))
        RuntimeCoordination.claimMaintenance().use { manager.rollback(it) }
        assertNull(manager.active()); assertEquals("before", readDatabase(paths.state))
    }
    @Test fun publisherProofRetainsBaselineAcrossLaterEmbeddedProgramReplacement() = fixture { paths, bundle ->
        val verifier = signatures(); val metadata = verifier.inspect(bundle); val proof = verifier.proof(bundle)
        val embedded = File(paths.rootfs, "usr/local/bin/opencode")
        embedded.outputStream().use { verifier.extract(bundle, metadata.manifestSha256, it) }
        val original = embedded.readBytes()
        val manager = OpenCodeUpdates(paths, verifier, baselineProofOverride = { proof })
        RuntimeCoordination.claimMaintenance().use { lease -> manager.install(bundle, metadata.manifestSha256, lease) { writeDatabase(paths.state, "after") } }
        embedded.writeText("a later rootfs or APK baseline, never executed")
        RuntimeCoordination.claimMaintenance().use { manager.rollback(it) }
        val retained = manager.active(true)!!
        assertArrayEquals(original, retained.first.readBytes()); assertEquals(metadata, retained.second)
        assertEquals("before", readDatabase(paths.state)); assertNotEquals(embedded, retained.first)
    }
    @Test fun everyInstallRenameBoundaryRecoversAndCommitRollsForward() {
        for (point in listOf("prepared", "old-state-moved", "new-state-moved", "selected", "probe-complete", "committed")) fixture { paths, bundle ->
            val metadata = signatures().inspect(bundle)
            val interrupted = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }) { if (it == point) throw PowerLoss() }
            RuntimeCoordination.claimMaintenance().use { lease ->
                try { interrupted.install(bundle, metadata.manifestSha256, lease) { writeDatabase(paths.state, "after") }; fail("fault was not reached: $point") } catch (_: PowerLoss) { }
            }
            val recovered = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }); assertTrue(recovered.recoveryPending())
            paths.prepare()
            RuntimeCoordination.claimMaintenance().use { recovered.recover(it) }
            assertFalse(recovered.recoveryPending()); assertEquals(if (point == "committed") "after" else "before", readDatabase(paths.state))
            assertEquals(point == "committed", recovered.active() != null)
            assertEquals("user project", File(paths.workspace, "project.txt").readText())
        }
    }
    @Test fun interruptedApprovedRollbackAlwaysCompletesAndKeepsNewerState() {
        for (point in listOf("rollback-prepared", "rollback-current-state-moved", "rollback-old-state-restored", "rollback-selected")) fixture { paths, bundle ->
            val normal = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }); val metadata = signatures().inspect(bundle)
            RuntimeCoordination.claimMaintenance().use { lease -> normal.install(bundle, metadata.manifestSha256, lease) { writeDatabase(paths.state, "after") } }
            val interrupted = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }) { if (it == point) throw PowerLoss() }
            RuntimeCoordination.claimMaintenance().use { lease -> try { interrupted.rollback(lease); fail("fault not reached") } catch (_: PowerLoss) { } }
            paths.prepare(); RuntimeCoordination.claimMaintenance().use { normal.recover(it) }
            assertNull(normal.active()); assertEquals("before", readDatabase(paths.state)); assertFalse(normal.recoveryPending())
            assertEquals("after", readDatabase(File(backups(paths), "after-update-state")))
        }
    }
    @Test fun confirmedIdentityExternalLinksAndChangedInstalledBytesAreRejected() = fixture { paths, bundle ->
        val manager = OpenCodeUpdates(paths, signatures(), baselineProofOverride = { null }); val metadata = signatures().inspect(bundle)
        RuntimeCoordination.claimMaintenance().use { lease -> rejected { manager.install(bundle, "b".repeat(64), lease) { fail("not confirmed") } } }
        Os.symlink(paths.workspace.path, File(paths.state, "external").path)
        RuntimeCoordination.claimMaintenance().use { lease -> rejected { manager.install(bundle, metadata.manifestSha256, lease) { fail("external state must not run") } } }
        assertFalse(manager.recoveryPending()); assertEquals("before", readDatabase(paths.state)); assertNull(manager.active())
        check(File(paths.state, "external").delete())
        RuntimeCoordination.claimMaintenance().use { lease -> manager.install(bundle, metadata.manifestSha256, lease) { } }
        val binary = manager.active()!!.first; Os.chmod(binary.path, 384); binary.appendBytes(byteArrayOf(1))
        rejected { manager.active(true) }
        RuntimeCoordination.claimMaintenance().use { manager.rollback(it) }; assertNull(manager.active())
    }
    companion object {
        internal fun removeOwnedTestTree(context: Context, root: File) {
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile && root.name.startsWith("update-"))
            io.github.opencodeandroid.backup.ArchiveTraversal.remove(root.toPath())
        }
    }
}
