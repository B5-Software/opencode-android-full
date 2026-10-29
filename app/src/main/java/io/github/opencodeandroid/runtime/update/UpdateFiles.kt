package io.github.opencodeandroid.runtime.update

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object UpdateFiles {
    fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    fun directory(file: File): File {
        check(!Files.isSymbolicLink(file.toPath()) && (file.isDirectory || file.mkdir())) { "更新目录无效" }
        Os.chmod(file.path, 448)
        return file
    }
    fun regular(file: File) { check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "更新文件无效" } }
    fun sync(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd) } finally { Os.close(fd) }
    }
    fun bytes(file: File, data: ByteArray) {
        check(!exists(file))
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        try { FileOutputStream(fd).use { it.write(data); it.fd.sync() } } finally { Os.close(fd) }
    }
    fun json(file: File, data: JSONObject) {
        check(!Files.isSymbolicLink(file.toPath()))
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(data.toString().toByteArray()); atomic.finishWrite(stream); Os.chmod(file.path, 384); sync(file.parentFile!!) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }
    fun readJson(file: File): JSONObject {
        check(!Files.isSymbolicLink(file.toPath()))
        val data = AtomicFile(file).openRead().use { input ->
            val buffer = ByteArray(32 * 1024 + 1); var total = 0
            while (total < buffer.size) { val n = input.read(buffer, total, buffer.size - total); if (n < 0) break; total += n }
            check(total <= 32 * 1024); buffer.copyOf(total)
        }
        return JSONObject(data.toString(Charsets.UTF_8))
    }
    fun removeJson(file: File) { AtomicFile(file).delete(); sync(file.parentFile!!) }
    fun move(source: File, destination: File) {
        check(exists(source) && !exists(destination) && !Files.isSymbolicLink(source.toPath())) { "更新目录切换条件不满足" }
        Os.rename(source.path, destination.path); sync(source.parentFile!!); if (source.parentFile != destination.parentFile) sync(destination.parentFile!!)
    }
    fun sha(file: File): String {
        regular(file)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    /** No-follow copy: live SQLite/WAL writers must already be stopped by the maintenance lease. */
    fun snapshot(source: File, destination: File, checkpoint: () -> Unit) {
        check(source.isDirectory && !Files.isSymbolicLink(source.toPath()) && !exists(destination))
        var entries = 0; var bytes = 0L
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            private fun target(path: Path) = destination.toPath().resolve(source.toPath().relativize(path)).toFile()
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(++entries <= 200_000 && source.toPath().relativize(path).nameCount <= 64); checkpoint()
                directory(target(path)); return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(++entries <= 200_000); checkpoint()
                val target = target(path)
                when {
                    attrs.isSymbolicLink -> {
                        val link = Os.readlink(path.toString())
                        val logical = if (link.startsWith("/opencode-state/")) source.toPath().resolve(link.removePrefix("/opencode-state/")) else path.parent.resolve(link)
                        check(logical.normalize().startsWith(source.toPath()) && (!link.startsWith('/') || link.startsWith("/opencode-state/"))) { "会话状态引用了外部目录，无法安全地试运行更新" }
                        Os.symlink(link, target.path)
                    }
                    attrs.isRegularFile -> {
                        bytes = Math.addExact(bytes, attrs.size()); check(bytes <= 20L * 1024 * 1024 * 1024)
                        check(destination.usableSpace > attrs.size() + 32L * 1024 * 1024) { "空间不足，原状态保持不变" }
                        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                            val fd = Os.open(target.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
                            try { FileOutputStream(fd).use { output -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; checkpoint(); output.write(buffer, 0, n) }; output.fd.sync() } } finally { Os.close(fd) }
                        }
                        Os.chmod(target.path, Os.lstat(path.toString()).st_mode and 511)
                    }
                    // Unix sockets/FIFOs are transient endpoints, not persistent session data.
                }
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Os.chmod(target(path).path, Os.lstat(path.toString()).st_mode and 511)
                sync(target(path)); return FileVisitResult.CONTINUE
            }
        })
    }
}
