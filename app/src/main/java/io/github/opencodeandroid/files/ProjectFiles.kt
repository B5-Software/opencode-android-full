package io.github.opencodeandroid.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class ProjectEntry(val path: String, val name: String, val directory: Boolean, val link: Boolean, val size: Long, val modified: Long)
data class ProjectDocument(val path: String, val text: String, val digest: String)
data class TrashedProjectEntry(val id: String, val path: String, val time: Long)
data class FileTransferProgress(val files: Int = 0, val bytes: Long = 0)

/** Paths belong to /workspace. Links can be listed and renamed, but are never opened. */
class ProjectFiles(private val workspace: File, private val trash: File) {
    private val root = workspace.absoluteFile.toPath().normalize()
    private val trashRoot = trash.absoluteFile.toPath().normalize()
    private val staging = trashRoot.parent.resolve("project-file-staging")

    init {
        require(!trashRoot.startsWith(root) && !root.startsWith(trashRoot))
        require(!staging.startsWith(root) && !root.startsWith(staging))
        for (directory in listOf(root, trashRoot, staging)) {
            require(!Files.isSymbolicLink(directory)) { "工作目录不能是符号链接" }
            Files.createDirectories(directory)
            require(directory.toFile().canonicalFile.toPath() == directory) { "工作目录路径无效" }
        }
        synchronized(cleanedStaging) {
            if (staging !in cleanedStaging) {
                Files.newDirectoryStream(staging).use { entries -> entries.forEach { entry ->
                    if (!Files.isSymbolicLink(entry) && runCatching { UUID.fromString(entry.fileName.toString()) }.isSuccess) {
                        val marker = entry.resolve(".operation")
                        if (Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.newInputStream(marker, NOFOLLOW_LINKS).use { it.bufferedReader().readLine() } == "opencode-copy-v1") deleteStaging(entry)
                    }
                } }
                cleanedStaging.add(staging)
            }
        }
    }

    private fun resolve(relative: String, allowRoot: Boolean = false, leafLink: Boolean = false): java.nio.file.Path {
        require(allowRoot || relative.isNotEmpty()) { "请选择一个文件" }
        require(!Files.isSymbolicLink(root)) { "工作目录不能是符号链接" }
        if (relative.isEmpty()) return root
        val components = relative.split('/')
        components.forEach(::validName)
        var path = root
        components.forEachIndexed { index, name ->
            path = path.resolve(name)
            require(leafLink && index == components.lastIndex || !Files.isSymbolicLink(path)) { "请在终端中处理符号链接" }
        }
        require(path.normalize().startsWith(root))
        return path
    }

    fun validName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.toByteArray(Charsets.UTF_8).size <= 255 && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "文件名无效" }
    }

    fun child(directory: String, name: String): String { validName(name); return if (directory.isEmpty()) name else "$directory/$name" }

    fun list(directory: String): List<ProjectEntry> {
        val parent = resolve(directory, allowRoot = true)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "目录不存在" }
        return Files.newDirectoryStream(parent).use { entries -> entries.map { file ->
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            ProjectEntry(child(directory, file.fileName.toString()), file.fileName.toString(), attributes.isDirectory, attributes.isSymbolicLink, attributes.size(), attributes.lastModifiedTime().toMillis())
        }.sortedWith(compareByDescending<ProjectEntry> { it.directory }.thenBy { it.name.lowercase() }) }
    }

    fun create(directory: String, name: String, folder: Boolean) {
        val path = resolve(child(directory, name))
        if (folder) Files.createDirectory(path) else Files.newByteChannel(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).close()
    }

    fun rename(relative: String, name: String) {
        validName(name)
        val original = resolve(relative, leafLink = true)
        val destination = resolve(child(relative.substringBeforeLast('/', ""), name), leafLink = true)
        if (original != destination) Files.move(original, destination)
    }

    private fun availableName(directory: String, requested: String): String {
        validName(requested)
        if (!Files.exists(resolve(child(directory, requested), leafLink = true), NOFOLLOW_LINKS)) return requested
        val extension = requested.substringAfterLast('.', "").takeIf { requested.lastIndexOf('.') > 0 && it.toByteArray().size <= 40 }
        val base = (if (extension != null) requested.dropLast(extension.length + 1) else requested).take(45)
        var suffix = 1
        while (true) {
            val candidate = "$base (${suffix++})" + if (extension == null) "" else ".$extension"
            if (!Files.exists(resolve(child(directory, candidate), leafLink = true), NOFOLLOW_LINKS)) return candidate
        }
    }

    /** Copy publishes a complete staged tree; move is a single same-volume rename. */
    fun transfer(relative: String, directory: String, move: Boolean, checkpoint: () -> Unit = {}, progress: (FileTransferProgress) -> Unit = {}): String {
        val source = resolve(relative, leafLink = true)
        val parent = resolve(directory, allowRoot = true)
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "目标目录不存在" }
        require(!parent.startsWith(source)) { "不能将文件夹放入自身或子目录" }
        if (move && source.parent == parent) return relative
        val name = availableName(directory, source.fileName.toString())
        val destination = resolve(child(directory, name))
        checkpoint()
        if (move) { Files.move(source, destination); return child(directory, name) }
        val operation = staging.resolve(UUID.randomUUID().toString())
        check(!Files.isSymbolicLink(staging))
        Files.createDirectory(operation)
        try {
            Files.write(operation.resolve(".operation"), "opencode-copy-v1\n".toByteArray(), CREATE_NEW, WRITE)
            val payload = operation.resolve("payload")
            var copied = FileTransferProgress()
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkpoint(); Files.createDirectory(payload.resolve(source.relativize(directory)))
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    checkpoint()
                    val target = payload.resolve(source.relativize(file))
                    if (attributes.isSymbolicLink) Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                    else {
                        require(attributes.isRegularFile) { "项目中含特殊设备或套接字，无法复制" }
                        Files.newByteChannel(file, READ, NOFOLLOW_LINKS).use { input ->
                            Files.newOutputStream(target, CREATE_NEW, WRITE).use { output ->
                                val stream = java.nio.channels.Channels.newInputStream(input)
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    checkpoint(); val count = stream.read(buffer); if (count < 0) break
                                    output.write(buffer, 0, count)
                                    copied = copied.copy(bytes = copied.bytes + count); progress(copied)
                                }
                            }
                        }
                        Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(file, NOFOLLOW_LINKS))
                        Files.setLastModifiedTime(target, attributes.lastModifiedTime())
                    }
                    copied = copied.copy(files = copied.files + 1); progress(copied)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    checkpoint()
                    val target = payload.resolve(source.relativize(directory))
                    Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS))
                    return FileVisitResult.CONTINUE
                }
            })
            checkpoint()
            resolve(directory, allowRoot = true)
            Files.move(payload, destination)
        } finally { deleteStaging(operation) }
        return child(directory, name)
    }

    private fun deleteStaging(entry: Path) {
        check(entry.parent == staging && runCatching { UUID.fromString(entry.fileName.toString()) }.isSuccess)
        if (!Files.exists(entry, NOFOLLOW_LINKS)) return
        Files.walkFileTree(entry, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(directory: Path, failure: java.io.IOException?): FileVisitResult { if (failure != null) throw failure; Files.delete(directory); return FileVisitResult.CONTINUE }
        })
    }

    private fun bytes(relative: String): ByteArray {
        val path = resolve(relative)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "请选择普通文件" }
        require(Files.size(path) <= 2 * 1024 * 1024) { "文件超过 2 MB，请使用 OpenCode 或终端编辑" }
        return Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { channel ->
            val output = java.io.ByteArrayOutputStream()
            val input = java.nio.channels.Channels.newInputStream(channel)
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 2 * 1024 * 1024) { "文件过大" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun read(relative: String): ProjectDocument {
        val bytes = bytes(relative)
        require(0.toByte() !in bytes) { "二进制文件可导出到其他应用打开" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() } catch (_: java.nio.charset.CharacterCodingException) { error("请使用 OpenCode 或终端编辑非 UTF-8 文件") }
        return ProjectDocument(relative, text, digest(bytes))
    }

    fun save(document: ProjectDocument, text: String): ProjectDocument {
        require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "文件过大" }
        val path = resolve(document.path)
        check(digest(bytes(document.path)) == document.digest) { "文件已被其他工具修改，请重新打开后再编辑" }
        val temporary = path.parent.resolve(".opencode-save-${UUID.randomUUID()}")
        try {
            Files.write(temporary, text.toByteArray(Charsets.UTF_8), CREATE_NEW, WRITE)
            Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(path, NOFOLLOW_LINKS))
            // Recheck before replacing; a symlink must never become a write target.
            resolve(document.path)
            check(digest(bytes(document.path)) == document.digest) { "文件已被其他工具修改，请重新打开后再编辑" }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return ProjectDocument(document.path, text, digest(text.toByteArray(Charsets.UTF_8)))
    }

    fun import(directory: String, requestedName: String, source: InputStream): String {
        val safeName = requestedName.substringAfterLast('/').substringAfterLast('\\').takeIf { runCatching { validName(it) }.isSuccess } ?: "imported-${UUID.randomUUID()}"
        val name = availableName(directory, safeName)
        val destination = resolve(child(directory, name))
        val temporary = destination.parent.resolve(".opencode-import-${UUID.randomUUID()}")
        try {
            Files.newOutputStream(temporary, CREATE_NEW, WRITE).use { source.copyTo(it) }
            Files.move(temporary, destination)
        } finally { Files.deleteIfExists(temporary) }
        return name
    }

    fun export(relative: String, target: OutputStream) {
        val path = resolve(relative)
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "请选择普通文件" }
        Files.newByteChannel(path, READ, NOFOLLOW_LINKS).use { java.nio.channels.Channels.newInputStream(it).copyTo(target) }
    }

    fun trash(relative: String) {
        val source = resolve(relative, leafLink = true)
        val entry = trashRoot.resolve(UUID.randomUUID().toString())
        require(!Files.isSymbolicLink(trashRoot))
        Files.createDirectory(entry)
        try {
            val metadata = Properties().apply { setProperty("path", relative); setProperty("time", System.currentTimeMillis().toString()) }
            Files.newOutputStream(entry.resolve("info.properties"), CREATE_NEW, WRITE).use { metadata.store(it, null) }
            Files.move(source, entry.resolve("payload"))
        } catch (failure: Exception) {
            Files.deleteIfExists(entry.resolve("info.properties")); Files.deleteIfExists(entry)
            throw failure
        }
    }

    fun trashEntries(): List<TrashedProjectEntry> {
        require(!Files.isSymbolicLink(trashRoot))
        return Files.newDirectoryStream(trashRoot).use { entries -> entries.mapNotNull { entry ->
            runCatching {
                require(!Files.isSymbolicLink(entry) && Files.exists(entry.resolve("payload"), NOFOLLOW_LINKS))
                val info = Properties().apply { Files.newInputStream(entry.resolve("info.properties"), NOFOLLOW_LINKS).use { load(it) } }
                TrashedProjectEntry(entry.fileName.toString(), info.getProperty("path"), info.getProperty("time").toLong())
            }.getOrNull()
        }.sortedByDescending { it.time } }
    }

    fun restore(id: String) {
        require(UUID.fromString(id).toString() == id)
        val entry = trashRoot.resolve(id)
        require(!Files.isSymbolicLink(trashRoot) && !Files.isSymbolicLink(entry))
        val info = Properties().apply { Files.newInputStream(entry.resolve("info.properties"), NOFOLLOW_LINKS).use { load(it) } }
        val destination = resolve(info.getProperty("path"), leafLink = true)
        require(Files.isDirectory(destination.parent, NOFOLLOW_LINKS)) { "原目录已不存在，请先恢复原目录" }
        Files.move(entry.resolve("payload"), destination)
        Files.delete(entry.resolve("info.properties")); Files.delete(entry)
    }
    private companion object { val cleanedStaging = mutableSetOf<Path>() }
}
