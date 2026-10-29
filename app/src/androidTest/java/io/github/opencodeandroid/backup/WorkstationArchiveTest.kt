package io.github.opencodeandroid.backup

import android.content.ContextWrapper
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.RuntimeCoordination
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.UpdateTransactionTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Only private fixture trees are decrypted. No restore activation or user-root replacement. */
class WorkstationArchiveTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun password() = "测试备份口令 fixture archive password".toCharArray()
    private fun fixture(action: (RuntimePaths, File) -> Unit) {
        val root = File(context.cacheDir, "update-archive-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = File(root, "source").apply { check(mkdir()) }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir() = files }
        val paths = RuntimePaths(wrapped).apply { prepare() }
        try { action(paths, root) } finally { UpdateTransactionTest.removeOwnedTestTree(context, root) }
    }
    private fun write(paths: RuntimePaths): ByteArray {
        val password = password()
        return try { ByteArrayOutputStream().apply { RuntimeCoordination.claimMaintenance().use { WorkstationArchive.write(paths, this, password, it) } }.toByteArray() }
        finally { password.fill('\u0000') }
    }
    private fun stage(bytes: ByteArray, destination: File): StagedWorkstation {
        check(destination.mkdir()); val password = password()
        return try { WorkstationArchive.stage(ByteArrayInputStream(bytes), password, destination) } finally { password.fill('\u0000') }
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Malformed archive was accepted") } catch (_: IOException) { } catch (_: IllegalStateException) { } }
    private fun malformed(roots: List<String> = listOf("workspace"), build: (DataOutputStream) -> Unit): ByteArray {
        val password = password()
        return try { ByteArrayOutputStream().apply {
            DataOutputStream(GZIPOutputStream(BackupEnvelope.encrypt(this, password))).use { data ->
                data.write("OCAFS001".toByteArray())
                val meta = JSONObject().put("format", 1).put("roots", JSONArray(roots)).toString().toByteArray()
                data.writeInt(meta.size); data.write(meta); build(data)
            }
        }.toByteArray() } finally { password.fill('\u0000') }
    }
    private fun name(data: DataOutputStream, value: String) { val bytes = value.toByteArray(); data.writeInt(bytes.size); data.write(bytes) }
    private fun entry(data: DataOutputStream, kind: Int, value: String) { data.writeByte(kind); name(data, value); data.writeInt(448); data.writeLong(1_700_000_000_000) }

    @Test fun completeTreesUnicodeModesSymlinksAndHardlinksRoundTrip() = fixture { paths, root ->
        File(paths.rootfs, "usr/bin").mkdirs(); File(paths.rootfs, "usr/bin/tool").apply { writeText("native Linux fixture, never executed"); Os.chmod(path, 493) }
        val project = File(paths.workspace, "项目").apply { mkdir(); Os.chmod(path, 488) }
        val original = File(project, "你好.txt").apply { writeText("preserve project contents"); setLastModified(1_700_000_000_000); Os.chmod(path, 416) }
        ArchiveLinks.link(paths.files, original, File(project, "hard.txt"), mutableMapOf())
        Os.symlink("你好.txt", File(project, "relative-link").path)
        val outside = File(root, "outside").apply { writeText("do not follow this external target"); setLastModified(1_600_000_000_000) }
        val outsideModified = outside.lastModified()
        Os.symlink(outside.path, File(project, "absolute-link").path)
        File(project, "emoji-📦.txt").writeText("supplementary Unicode")
        Os.symlink("emoji-📦.txt", File(project, "emoji-🔗").path)
        Os.mkfifo(File(project, "named-pipe").path, 432); Os.chmod(File(project, "named-pipe").path, 432)
        File(paths.home, ".ssh").mkdir(); File(paths.home, ".ssh/id_fixture").writeText("fixture-private-content")
        File(paths.state, "history.db").writeText("fixture database and all its sidecars")
        File(paths.state, "history.db-wal").writeText("fixture WAL")
        File(paths.state, "config/opencode").mkdirs(); File(paths.state, "config/opencode/service-dev.json").writeText("regenerate local password")
        File(paths.desktop, "chromium").mkdir(); File(paths.desktop, "chromium/Preferences").writeText("fixture desktop profile")
        File(paths.desktop, "private").mkdir(); File(paths.desktop, "private/gateway.json").writeText("regenerate gateway")
        File(paths.config, "server-credential").writeText("device-bound ciphertext, omit")
        File(paths.config, "opencode-active.json").writeText("fixture selection")
        val archive = write(paths)
        assertFalse(archive.toString(Charsets.ISO_8859_1).contains("fixture-private-content"))
        val destination = File(root, "staged"); val descriptor = stage(archive, destination)
        assertEquals(7, descriptor.roots.size); assertTrue(descriptor.entries > 15)
        val restored = File(destination, "workspace/项目/你好.txt")
        assertEquals(original.readText(), restored.readText()); assertEquals(416, Os.lstat(restored.canonicalPath).st_mode and 511)
        assertEquals(original.lastModified(), restored.lastModified()); assertEquals(488, Os.lstat(File(destination, "workspace/项目").path).st_mode and 511)
        assertTrue(Files.isSameFile(restored.toPath(), File(destination, "workspace/项目/hard.txt").toPath()))
        assertEquals("你好.txt", Os.readlink(File(destination, "workspace/项目/relative-link").path))
        assertEquals(outside.path, Os.readlink(File(destination, "workspace/项目/absolute-link").path))
        assertEquals("supplementary Unicode", File(destination, "workspace/项目/emoji-📦.txt").readText())
        assertEquals("emoji-📦.txt", Os.readlink(File(destination, "workspace/项目/emoji-🔗").path))
        assertTrue(android.system.OsConstants.S_ISFIFO(Os.lstat(File(destination, "workspace/项目/named-pipe").path).st_mode))
        assertEquals(432, Os.lstat(File(destination, "workspace/项目/named-pipe").path).st_mode and 511)
        assertEquals("fixture WAL", File(destination, "opencode-state/history.db-wal").readText())
        assertEquals("fixture desktop profile", File(destination, "desktop-state/chromium/Preferences").readText())
        assertFalse(File(destination, "desktop-state/private").exists()); assertFalse(File(destination, "runtime-config/server-credential").exists())
        assertFalse(File(destination, "opencode-state/config/opencode/service-dev.json").exists())
        assertEquals("fixture selection", File(destination, "runtime-config/opencode-active.json").readText())
        assertEquals("do not follow this external target", outside.readText())
        assertEquals(outsideModified, outside.lastModified())
    }
    @Test fun traversalSymlinkParentsDuplicatePathsAndHugeLengthsAreRejected() = fixture { _, root ->
        val outside = File(root, "outside").apply { mkdir() }
        val attacks = listOf<(DataOutputStream) -> Unit>(
            { data -> entry(data, 1, "workspace"); entry(data, 2, "workspace/../../escape"); data.writeLong(0) },
            { data -> entry(data, 1, "workspace"); entry(data, 3, "workspace/link"); name(data, outside.path); entry(data, 2, "workspace/link/pwn"); data.writeLong(0) },
            { data -> entry(data, 1, "workspace"); entry(data, 2, "workspace/x"); data.writeLong(0); entry(data, 2, "workspace/x"); data.writeLong(0) },
            { data -> entry(data, 1, "workspace"); entry(data, 2, "workspace/x"); data.writeLong(Long.MAX_VALUE) },
            { data -> data.writeByte(2); data.writeInt(Int.MAX_VALUE) },
        )
        for ((index, attack) in attacks.withIndex()) rejected { stage(malformed(build = attack), File(root, "attack-$index")) }
        assertTrue(outside.list()!!.isEmpty()); assertFalse(File(root, "escape").exists())
    }
    @Test fun countMismatchUnknownTypesAndPostFooterBytesCannotBecomeAStagedArchive() = fixture { _, root ->
        val attacks = listOf<(DataOutputStream) -> Unit>(
            { data -> entry(data, 1, "workspace"); data.writeByte(0); data.writeLong(0); data.writeLong(0) },
            { data -> data.writeByte(9) },
            { data -> entry(data, 1, "workspace"); data.writeByte(0); data.writeLong(1); data.writeLong(0); data.write(1) },
        )
        for ((index, attack) in attacks.withIndex()) rejected { stage(malformed(build = attack), File(root, "invalid-$index")) }
    }
    @Test fun tailCorruptionAfterAuthenticatedSegmentsNeverReturnsAnAcceptableStage() = fixture { paths, root ->
        val data = ByteArray(2 * 1024 * 1024 + 77).also(SecureRandom()::nextBytes)
        val original = File(paths.workspace, "large.bin").apply { writeBytes(data) }
        val archive = write(paths); assertTrue(archive.size > 2 * 1024 * 1024)
        val corrupt = archive.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejected { stage(corrupt, File(root, "corrupt")) }
        rejected { stage(archive.copyOf(archive.size - 11), File(root, "truncated")) }
        assertArrayEquals(data, original.readBytes())
    }
    @Test fun physicalLinkRecordsUseTheRuntimeEmulationWithoutKernelLinkPermission() = fixture { _, root ->
        val archive = malformed(listOf("rootfs", "workspace")) { data ->
            entry(data, 1, "rootfs"); entry(data, 1, "workspace")
            entry(data, 2, "workspace/first"); data.writeLong(6); data.write("shared".toByteArray())
            entry(data, 4, "workspace/second"); name(data, "workspace/first")
            data.writeByte(0); data.writeLong(4); data.writeLong(6)
        }
        val destination = File(root, "links"); stage(archive, destination)
        val first = File(destination, "workspace/first"); val second = File(destination, "workspace/second")
        assertTrue(Files.isSymbolicLink(first.toPath())); assertTrue(Files.isSameFile(first.toPath(), second.toPath()))
        assertEquals("shared", first.readText()); second.writeText("changed"); assertEquals("changed", first.readText())
    }
    @Test fun modeZeroMountPlaceholdersAreCapturedWithoutChangingLivePermissionsAndRemainZeroAfterRebase() = fixture { paths, root ->
        check(paths.rootfs.mkdir()); val mount = File(paths.rootfs, "workspace").apply { check(mkdir()); Os.chmod(path, 0) }
        File(paths.workspace, "live-data").writeText("persistent bind data")
        val archive = write(paths); assertEquals(0, Os.lstat(mount.path).st_mode and 511)
        val destination = File(root, "zero-mode"); val descriptor = stage(archive, destination)
        val restored = File(destination, "rootfs/workspace")
        assertEquals(0, Os.lstat(restored.path).st_mode and 511)
        ArchiveLinks.rebase(destination, paths.files, descriptor.roots)
        assertEquals(0, Os.lstat(restored.path).st_mode and 511)
        assertEquals("persistent bind data", File(destination, "workspace/live-data").readText())
        assertEquals(0, Os.lstat(mount.path).st_mode and 511)
    }
    @Test fun deviceBoundCredentialAndRecoveryFilesCannotBeInjectedThroughRuntimeConfig() = fixture { _, root ->
        val archive = malformed(listOf("runtime-config")) { data ->
            entry(data, 1, "runtime-config"); entry(data, 2, "runtime-config/rootfs-update.json"); data.writeLong(0)
        }
        val destination = File(root, "forbidden-runtime-config")
        rejected { stage(archive, destination) }
        assertFalse(File(destination, "runtime-config/rootfs-update.json").exists())
    }
}
