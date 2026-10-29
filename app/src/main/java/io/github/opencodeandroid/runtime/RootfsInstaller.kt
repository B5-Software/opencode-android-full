package io.github.opencodeandroid.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.AtomicFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.util.zip.GZIPInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

/** Debian remains APK-pinned; independent OpenCode selection has its own signature verification. */
class RootfsInstaller(private val context: Context, private val paths: RuntimePaths) {
    private val updateJournal get() = AtomicFile(File(paths.config, "rootfs-update.json"))

    fun updateAvailable(): Boolean = paths.installed && runCatching {
        val bundled = bundledManifest()
        val installed = JSONObject(File(paths.rootfs, ".installed-pack").readText())
        bundled.getString("sha256") != installed.getString("sha256")
    }.getOrDefault(false)

    /** Complete or roll back an interrupted directory switch before starting guest processes. */
    fun recoveryPending() = updateJournal.baseFile.exists() || File(updateJournal.baseFile.path + ".bak").exists()
    fun recoverInterruptedUpdate(log: (String) -> Unit) {
        if (!recoveryPending()) return
        val journal = JSONObject(updateJournal.openRead().bufferedReader().use { it.readText() })
        val name = journal.getString("backup")
        check(name.matches(Regex("rootfs-[0-9]+-[a-f0-9-]{36}"))) { "Invalid runtime recovery record" }
        val backup = File(paths.backups, name)
        check(!Files.isSymbolicLink(backup.toPath()))
        val previouslyInstalled = journal.optBoolean("previouslyInstalled", true)
        if (!paths.rootfs.exists()) {
            check(backup.isDirectory && (!previouslyInstalled || File(backup, ".installed-pack").isFile) && backup.renameTo(paths.rootfs)) { "Runtime recovery needs the preserved backup: $name" }
            log("Restored the previous Debian environment after an interrupted update.")
        }
        check(paths.installed || (!previouslyInstalled && paths.rootfs.isDirectory)) { "Runtime recovery preserved an incomplete environment for inspection" }
        updateJournal.delete()
    }

    fun install(log: (String) -> Unit) = installBundled(false, log)
    fun updateBundled(log: (String) -> Unit) = installBundled(true, log)
    fun repairBundled(log: (String) -> Unit) = installBundled(true, log, preserveIncomplete = true)

    private fun bundledManifest() = JSONObject(context.assets.open("runtime/pack.json").bufferedReader().use { it.readText() })

    private fun installBundled(upgrade: Boolean, log: (String) -> Unit, preserveIncomplete: Boolean = false) {
        paths.prepare()
        recoverInterruptedUpdate(log)
        if (upgrade) check(paths.installed || (preserveIncomplete && paths.rootfs.isDirectory)) { "Install Debian before updating it" }
        else check(!paths.installed) { "Debian is already installed; use the explicit update action." }
        val manifest = bundledManifest()
        check(manifest.getString("abi") in Build.SUPPORTED_ABIS) { "Runtime pack does not match this device ABI" }
        check(manifest.getString("suite") == "trixie")
        val archive = manifest.getString("archive")
        check(archive == "debian.pack")
        log("Checking bundled Debian 13 archive…")
        val hash = MessageDigest.getInstance("SHA-256")
        context.assets.open("runtime/$archive").use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        val digest = hash.digest().joinToString("") { "%02x".format(it) }
        check(digest == manifest.getString("sha256")) { "Debian pack checksum mismatch" }
        check(context.filesDir.usableSpace > manifest.getLong("minimumFreeBytes")) { "Not enough free storage to install Debian" }
        val stage = File(paths.files, "rootfs-installing")
        // Only this disposable staging tree can be cleaned. Never follow symlinks.
        deleteStage(stage)
        check(stage.mkdir())
        val hardLinks = mutableListOf<Pair<String, String>>()
        var total = 0L
        var count = 0
        try {
            context.assets.open("runtime/$archive").use { compressed ->
                GZIPInputStream(compressed).use { xz ->
                    TarArchiveInputStream(xz).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val name = entry.name.removePrefix("./").trimEnd('/')
                            if (name.isEmpty() || name == ".") continue
                            val target = safeTarget(stage, name)
                            check(++count <= 500_000) { "Too many archive entries" }
                            check(entry.size >= 0 && entry.size <= 512L * 1024 * 1024) { "Oversized archive entry" }
                            total += entry.size
                            check(total <= 4L * 1024 * 1024 * 1024) { "Oversized runtime archive" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                            when {
                                entry.isDirectory -> { check(!Files.isSymbolicLink(target.toPath())); check(target.isDirectory || target.mkdir()); Os.chmod(target.path, entry.mode and 511) }
                                entry.isSymbolicLink -> {
                                    check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
                                    // Absolute Linux links are data, never followed by this installer.
                                    Os.symlink(entry.linkName, target.path)
                                }
                                entry.isLink -> hardLinks += name to entry.linkName.removePrefix("./")
                                entry.isFile -> {
                                    check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
                                    target.outputStream().use { output -> tar.copyTo(output, 128 * 1024) }
                                    Os.chmod(target.path, entry.mode and 511)
                                }
                                else -> error("Unsupported archive node: $name")
                            }
                            if (count % 2000 == 0) log("Extracted $count entries…")
                        }
                    }
                }
            }
            hardLinks.forEach { (name, source) ->
                val from = safeTarget(stage, source)
                check(OsConstants.S_ISREG(Os.lstat(from.path).st_mode))
                val target = safeTarget(stage, name)
                try { Os.link(from.path, target.path) }
                catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.EACCES && failure.errno != OsConstants.EPERM && failure.errno != OsConstants.EXDEV) throw failure
                    // SELinux may deny host link(2). Initial archive aliases can safely be
                    // materialized; guest-created links use PRoot's link2symlink extension.
                    from.copyTo(target, overwrite = false)
                    Os.chmod(target.path, Os.lstat(from.path).st_mode and 511)
                }
            }
            installOpenCode(stage, manifest, log)
            File(stage, ".installed-pack").writeText(manifest.toString())
            if (upgrade) {
                val backup = File(paths.backups, "rootfs-${System.currentTimeMillis()}-${UUID.randomUUID()}")
                check(!backup.exists() && !Files.isSymbolicLink(paths.rootfs.toPath()))
                val journalStream = updateJournal.startWrite()
                try {
                    journalStream.write(JSONObject().put("backup", backup.name).put("previouslyInstalled", paths.installed).toString().toByteArray())
                    updateJournal.finishWrite(journalStream)
                } catch (failure: Throwable) { updateJournal.failWrite(journalStream); throw failure }
                check(paths.rootfs.renameTo(backup)) { "Could not preserve the previous Debian environment" }
                if (!stage.renameTo(paths.rootfs)) {
                    check(backup.renameTo(paths.rootfs)) { "Runtime switch failed; previous environment is preserved in ${backup.name}" }
                    updateJournal.delete()
                    error("Could not activate the new Debian environment; previous environment restored")
                }
                updateJournal.delete()
                log("Previous Debian preserved in backups/${backup.name}. Workspace, home and service data retained.")
            } else {
                check(!paths.rootfs.exists()) { "An incomplete rootfs exists. Preserve it and recover manually." }
                check(stage.renameTo(paths.rootfs)) { "Could not atomically install rootfs" }
            }
            log("Debian 13 ${if (upgrade) "updated" else "installed"} ($count entries).")
        } catch (failure: Throwable) {
            deleteStage(stage)
            throw failure
        }
    }

    fun ensureOpenCode(log: (String) -> Unit): String {
        check(paths.installed)
        io.github.opencodeandroid.runtime.update.OpenCodeUpdates(paths).active(verifyBinary = true)?.let { return it.second.version }
        val published = io.github.opencodeandroid.runtime.update.PublishedOpenCode(paths)
        val program = safeTarget(paths.rootfs, "usr/local/bin/opencode")
        published.stored()?.let { return published.verify(program, it).version }
        val manifest = JSONObject(context.assets.open("runtime/pack.json").bufferedReader().use { it.readText() })
        check(manifest.getString("abi") in Build.SUPPORTED_ABIS)
        val info = manifest.optJSONObject("opencode") ?: error("Official OpenCode dev executable has not been bundled")
        val target = safeTarget(paths.rootfs, "usr/local/bin/opencode")
        if (target.exists()) {
            val hash = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
            check(hash.digest().joinToString("") { "%02x".format(it) } == info.getString("sha256")) { "OpenCode differs from this APK. Back up and perform an explicit runtime update." }
        } else installOpenCode(paths.rootfs, manifest, log)
        published.bundled()?.let { proof -> published.verify(program, proof); published.store(paths.rootfs, proof) }
        return info.getString("version")
    }

    private fun installOpenCode(root: File, manifest: JSONObject, log: (String) -> Unit) {
        val info = manifest.optJSONObject("opencode") ?: return
        check(info.getString("archive") == "opencode.pack")
        val expectedSize = info.getLong("size")
        check(expectedSize in 1..512L * 1024 * 1024)
        check(root.usableSpace > expectedSize + 128 * 1024 * 1024)
        val target = safeTarget(root, "usr/local/bin/opencode")
        val pending = safeTarget(root, "usr/local/bin/opencode-installing")
        check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(!Files.isSymbolicLink(pending.toPath()))
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        log("Installing official OpenCode ${info.getString("version")}…")
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            context.assets.open("runtime/opencode.pack").use { compressed ->
                GZIPInputStream(compressed).use { input ->
                    pending.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n
                            check(total <= expectedSize)
                            hash.update(buffer, 0, n); output.write(buffer, 0, n)
                        }
                    }
                }
            }
            check(total == expectedSize && hash.digest().joinToString("") { "%02x".format(it) } == info.getString("sha256")) { "OpenCode checksum mismatch" }
            Os.chmod(pending.path, 493)
            check(pending.renameTo(target))
            val published = io.github.opencodeandroid.runtime.update.PublishedOpenCode(paths)
            published.bundled()?.let { proof -> published.verify(target, proof); published.store(root, proof) }
        } finally { pending.delete() }
    }

    internal fun safeTarget(root: File, name: String): File {
        val parts = name.split('/')
        require(!name.startsWith('/') && parts.none { it == ".." || it.isEmpty() } && '\u0000' !in name) { "Unsafe tar path" }
        var current = root
        parts.dropLast(1).forEach { part ->
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "Tar entry traverses a symlink" }
        }
        return File(root, name)
    }

    private fun deleteStage(stage: File) {
        check(stage.absoluteFile.parentFile == paths.files && stage.name == "rootfs-installing")
        if (!Files.exists(stage.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(stage.toPath()).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
