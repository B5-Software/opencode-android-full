package io.github.opencodeandroid.backup

import android.system.Os
import android.system.OsConstants
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.ArrayDeque

/** Private staging/owned cleanup only. No-follow, bounded depth, including mode-000 directories. */
internal object ArchiveTraversal {
    private data class Frame(val path: Path, val mode: Int, val modified: Long,
        val stream: DirectoryStream<Path>, val iterator: Iterator<Path>)
    fun walk(root: Path, remove: Boolean = false, leaf: (Path, BasicFileAttributes) -> Unit) {
        val stack = ArrayDeque<Frame>()
        fun enter(path: Path) {
            check(stack.size < 512) { "备份目录层级过深" }
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attrs.isDirectory)
            val mode = Os.lstat(path.toString()).st_mode and 511
            Os.chmod(path.toString(), mode or 448)
            try {
                val stream = Files.newDirectoryStream(path)
                stack.addLast(Frame(path, mode, attrs.lastModifiedTime().toMillis(), stream, stream.iterator()))
            } catch (failure: Throwable) { Os.chmod(path.toString(), mode); throw failure }
        }
        fun restore(frame: Frame) {
            val fd = Os.open(frame.path.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                Files.setLastModifiedTime(frame.path, FileTime.fromMillis(frame.modified)); Os.chmod(frame.path.toString(), frame.mode); Os.fsync(fd)
            } finally { Os.close(fd) }
        }
        try {
            enter(root)
            while (stack.isNotEmpty()) {
                val frame = stack.last
                if (frame.iterator.hasNext()) {
                    val path = frame.iterator.next()
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (attrs.isDirectory) enter(path) else leaf(path, attrs)
                } else {
                    stack.removeLast(); frame.stream.close()
                    if (remove) Files.delete(frame.path) else restore(frame)
                }
            }
        } catch (failure: Throwable) {
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                runCatching { frame.stream.close(); restore(frame) }.onFailure { failure.addSuppressed(it) }
            }
            throw failure
        }
    }
    fun remove(root: Path) = walk(root, true) { path, _ -> Files.delete(path) }
}
