package io.github.opencodeandroid.backup

import android.system.Os
import android.system.OsConstants
import io.github.opencodeandroid.runtime.RuntimeCoordination
import io.github.opencodeandroid.runtime.RuntimePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class StagedWorkstation(val metadata: JSONObject, val roots: Set<String>, val entries: Long, val dataBytes: Long)

/** Bounded filesystem records inside Tink Streaming AEAD, never an executable self-extracting file. */
internal object WorkstationArchive {
    private val magic = "OCAFS001".toByteArray(Charsets.US_ASCII)
    private val roots = setOf("rootfs", "workspace", "home", "opencode-state", "desktop-state", "opencode-runtimes", "runtime-config")
    private const val MAX_ENTRIES = 2_000_000L
    private const val MAX_DATA = 1L shl 50 // Below Tink's 1 MiB-segment plaintext limit; real free space is checked separately.
    private const val RESERVE = 64L * 1024 * 1024
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun encoded(value: String): ByteArray = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)).let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
    private fun string(output: DataOutputStream, value: String) { val bytes = encoded(value); check(bytes.size in 1..4096); output.writeInt(bytes.size); output.write(bytes) }
    private fun string(input: DataInputStream): String { val size = input.readInt(); check(size in 1..4096); return utf8(ByteArray(size).also(input::readFully)) }
    private fun path(value: String, allowed: Set<String>): List<String> {
        check(!value.startsWith('/') && '\u0000' !in value)
        return value.split('/').also { pieces -> check(pieces.size <= 256 && pieces.first() in allowed && pieces.none { it.isEmpty() || it == "." || it == ".." }) }
    }
    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun target(destination: File, name: String, allowed: Set<String>): File {
        val pieces = path(name, allowed); var parent = destination
        for (part in pieces.dropLast(1)) {
            parent = File(parent, part); check(Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) { "备份路径的父目录无效" }
        }
        return File(parent, pieces.last())
    }
    private fun sources(paths: RuntimePaths) = linkedMapOf("rootfs" to paths.rootfs, "workspace" to paths.workspace,
        "home" to paths.home, "opencode-state" to paths.state, "desktop-state" to paths.desktop,
        "opencode-runtimes" to paths.openCodeVersions, "runtime-config" to paths.config).filterValues(::exists)
    private fun included(name: String, directory: Boolean): Boolean {
        if (name.startsWith("runtime-config/")) return !directory && name in setOf("runtime-config/opencode-active.json", "runtime-config/opencode-active.json.bak")
        if (name == "opencode-state/config/opencode/service-dev.json") return false
        if (name in setOf("desktop-state/run", "desktop-state/private", "desktop-state/session-state", "desktop-state/stop-request", "desktop-state/xfce-ready")) return false
        return true
    }

    /** Complete Linux trees, including apt/desktop customizations; host-derived secrets are regenerated. */
    // These PRoot mount placeholders contain no guest-visible persistent data. Live persistent
    // mounts are captured through their separate roots; /dev, /proc, /sys and tmp are rebuilt.
    private fun mountPlaceholder(name: String) = name in setOf("rootfs/workspace", "rootfs/root", "rootfs/opencode-state",
        "rootfs/desktop-state", "rootfs/android-runtime", "rootfs/tmp", "rootfs/dev", "rootfs/proc", "rootfs/sys")
    fun write(paths: RuntimePaths, destination: OutputStream, password: CharArray, lease: RuntimeCoordination.MaintenanceLease,
              settings: JSONObject = JSONObject(), progress: (Long) -> Unit = {}) {
        lease.checkCurrent()
        val sources = sources(paths)
        sources.values.forEach { check(Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)) { "工作站根目录不能是符号链接" } }
        val metadata = JSONObject().put("format", 1).put("package", paths.applicationContext.packageName).put("abi", android.os.Build.SUPPORTED_ABIS.first())
            .put("filesRoot", paths.files.path)
            .put("createdUtcMillis", System.currentTimeMillis()).put("hostVersionCode", paths.applicationContext.packageManager.getPackageInfo(paths.applicationContext.packageName, 0).longVersionCode)
            .put("roots", JSONArray(sources.keys.toList())).put("settings", settings)
        val header = metadata.toString().toByteArray(Charsets.UTF_8); check(header.size <= 65536)
        val encrypted = BackupEnvelope.encrypt(destination, password)
        val gzip = object : GZIPOutputStream(encrypted, 128 * 1024) { init { def.setLevel(Deflater.BEST_SPEED) } }
        DataOutputStream(gzip).use { output ->
            output.write(magic); output.writeInt(header.size); output.write(header)
            var entries = 0L; var bytes = 0L
            val copyBuffer = ByteArray(128 * 1024)
            val links = mutableMapOf<String, String>()
            fun entry(kind: Int, name: String, attributes: BasicFileAttributes, file: Path) {
                check(++entries <= MAX_ENTRIES); lease.checkCurrent(); progress(bytes); path(name, sources.keys)
                output.writeByte(kind); string(output, name); output.writeInt(Os.lstat(file.toString()).st_mode and 511); output.writeLong(attributes.lastModifiedTime().toMillis())
            }
            for ((rootName, root) in sources) Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                private fun name(file: Path) = rootName + root.toPath().relativize(file).toString().let { if (it.isEmpty()) "" else "/$it" }
                override fun preVisitDirectory(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    val name = name(file)
                    if (!included(name, true)) return FileVisitResult.SKIP_SUBTREE
                    entry(1, name, attributes, file); return if (mountPlaceholder(name)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult {
                    if (!mountPlaceholder(name(file))) throw failure
                    val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    check(attrs.isDirectory); entry(1, name(file), attrs, file); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    val name = name(file); if (!included(name, false)) return FileVisitResult.CONTINUE
                    when {
                        attributes.isSymbolicLink -> { entry(3, name, attributes, file); string(output, Os.readlink(file.toString())) }
                        attributes.isRegularFile -> {
                            val stat = Os.lstat(file.toString()); val key = "${stat.st_dev}:${stat.st_ino}"
                            val previous = if (stat.st_nlink > 1) links.putIfAbsent(key, name) else null
                            if (previous != null) { entry(4, name, attributes, file); string(output, previous) }
                            else {
                                entry(2, name, attributes, file); val size = attributes.size(); check(size >= 0 && bytes <= MAX_DATA - size); output.writeLong(size)
                                var copied = 0L
                                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                                    val buffer = copyBuffer
                                    while (copied < size) { lease.checkCurrent(); val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt()); check(count > 0); output.write(buffer, 0, count); copied += count; progress(bytes + copied) }
                                    check(input.read() == -1) { "文件在备份过程中发生变化" }
                                }
                                bytes += size
                            }
                        }
                        OsConstants.S_ISFIFO(Os.lstat(file.toString()).st_mode) -> entry(5, name, attributes, file)
                        // Process sockets and device nodes are regenerated by the runtime.
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            output.writeByte(0); output.writeLong(entries); output.writeLong(bytes)
        }
    }

    /** Stage only: no user files are activated, and every encrypted segment must authenticate to EOF. */
    fun stage(source: InputStream, password: CharArray, destinationDirectory: File, progress: (Long) -> Unit = {}): StagedWorkstation {
        check(Files.isDirectory(destinationDirectory.toPath(), LinkOption.NOFOLLOW_LINKS) && destinationDirectory.list()!!.isEmpty())
        val destination = destinationDirectory.canonicalFile
        Os.chmod(destination.path, 448)
        var entries = 0L; var bytes = 0L
        val directoryMetadata = File(destination, ".directory-metadata")
        var result: StagedWorkstation? = null
        BackupEnvelope.decrypt(source, password).use { decrypted ->
            val nonClosing = object : FilterInputStream(decrypted) { override fun close() {} }
            GZIPInputStream(nonClosing, 128 * 1024).use { gzip ->
                val input = DataInputStream(gzip)
                check(ByteArray(8).also(input::readFully).contentEquals(magic)) { "不支持的工作站数据格式" }
                val metadataSize = input.readInt(); check(metadataSize in 1..65536)
                val metadata = JSONObject(utf8(ByteArray(metadataSize).also(input::readFully)).also(BackupJson::check))
                check(metadata.opt("format") == 1)
                val originalFilesRoot = metadata.optString("filesRoot", "")
                check(originalFilesRoot.isEmpty() || (originalFilesRoot.startsWith('/') && originalFilesRoot.length <= 4096 && '\u0000' !in originalFilesRoot))
                val declared = metadata.getJSONArray("roots"); check(declared.length() <= roots.size)
                val allowed = (0 until declared.length()).map { declared.getString(it) }.toSet()
                check(allowed.size == declared.length() && roots.containsAll(allowed))
                val seenRoots = mutableSetOf<String>()
                val hardlinks = mutableMapOf<String, ArchiveLinks.Group>()
                val copyBuffer = ByteArray(128 * 1024)
                DataOutputStream(directoryMetadata.outputStream().buffered()).use { directoryOutput ->
                    while (true) {
                        val kind = input.readUnsignedByte()
                        if (kind == 0) { check(input.readLong() == entries && input.readLong() == bytes && seenRoots == allowed); check(input.read() == -1) { "备份包含额外数据" }; break }
                        check(kind in 1..5 && ++entries <= MAX_ENTRIES)
                        val name = string(input); val file = target(destination, name, allowed); check(!exists(file)) { "备份包含重复路径" }
                        check(included(name, kind == 1)) { "备份包含不可迁移的运行时配置" }
                        val mode = input.readInt(); check(mode in 0..511); val modified = input.readLong()
                        when (kind) {
                            1 -> {
                                check(destination.usableSpace > RESERVE && file.mkdir()); Os.chmod(file.path, 448)
                                if ('/' !in name) seenRoots += name
                                val encoded = encoded(name)
                                directoryOutput.writeInt(encoded.size); directoryOutput.write(encoded); directoryOutput.writeInt(mode); directoryOutput.writeLong(modified); directoryOutput.writeInt(encoded.size + 16)
                            }
                            2 -> {
                                val size = input.readLong(); check(size >= 0 && bytes <= MAX_DATA - size && destination.usableSpace > size + RESERVE) { "备份过大或空间不足" }
                                val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
                                try { FileOutputStream(fd).use { output ->
                                    var copied = 0L; val buffer = copyBuffer
                                    while (copied < size) { val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt()); check(count > 0); output.write(buffer, 0, count); copied += count; progress(bytes + copied) }
                                }
                                    Os.chmod(file.path, mode); Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified)); Os.fsync(fd)
                                } finally { Os.close(fd) }
                                bytes += size
                            }
                            3 -> {
                                val link = string(input); check('\u0000' !in link)
                                Os.symlink(if (originalFilesRoot.isEmpty()) link else ArchiveLinks.relocated(link, originalFilesRoot, destination, allowed), file.path)
                                check(io.github.opencodeandroid.runtime.NativeHost.setSymlinkModifiedTime(encoded(file.path), modified) == 0) { "无法恢复符号链接时间戳" }
                            }
                            4 -> {
                                val original = target(destination, string(input), allowed)
                                val content = hardlinks[original.path]?.content ?: original
                                check(Files.isRegularFile(content.toPath(), LinkOption.NOFOLLOW_LINKS))
                                check(Os.lstat(content.path).st_mode and 511 == mode && Files.getLastModifiedTime(content.toPath(), LinkOption.NOFOLLOW_LINKS).toMillis() == modified)
                                ArchiveLinks.link(destination, original, file, hardlinks)
                            }
                            5 -> {
                                check(destination.usableSpace > RESERVE)
                                Os.mkfifo(file.path, mode)
                                Os.chmod(file.path, mode)
                                check(io.github.opencodeandroid.runtime.NativeHost.setSymlinkModifiedTime(encoded(file.path), modified) == 0) { "无法恢复命名管道时间戳" }
                            }
                        }
                        progress(bytes)
                    }
                }
                result = StagedWorkstation(metadata, allowed, entries, bytes)
            }
            // Gzip may stop at its trailer while AEAD still has a final segment to authenticate.
            val buffer = ByteArray(128 * 1024); while (decrypted.read(buffer) >= 0) progress(bytes)
        }
        // Directory modes are applied in reverse order only after whole-file authentication.
        RandomAccessFile(directoryMetadata, "r").use { records ->
            var end = records.length()
            while (end > 0) {
                check(end >= 4); records.seek(end - 4); val length = records.readInt(); check(length in 17..4112 && end >= length + 4)
                val start = end - length - 4; records.seek(start); val nameLength = records.readInt(); check(nameLength in 1..4096)
                val name = utf8(ByteArray(nameLength).also(records::readFully)); val mode = records.readInt(); val modified = records.readLong()
                check(length == nameLength + 16); val file = target(destination, name, result!!.roots)
                check(Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                try { Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(modified)); Os.chmod(file.path, mode); Os.fsync(fd) }
                finally { Os.close(fd) }
                end = start
            }
        }
        check(directoryMetadata.delete()); io.github.opencodeandroid.runtime.update.UpdateFiles.sync(destination)
        return result!!
    }
}
