package io.github.opencodeandroid.backup

import android.system.Os
import io.github.opencodeandroid.runtime.update.UpdateFiles
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Same link representation as the pinned PRoot build (without USERLAND), never link(2) or a copy. */
internal object ArchiveLinks {
    internal class Group(val intermediate: File, var content: File, var count: Int)
    fun link(tree: File, original: File, alias: File, groups: MutableMap<String, Group>) {
        check(!Files.exists(alias.toPath(), LinkOption.NOFOLLOW_LINKS))
        val existing = groups[original.path]
        val group = if (existing != null) {
            check(existing.count < 9999) { "PRoot 链接数量超过当前格式上限" }
            val content = File(existing.intermediate.path + "." + (existing.count + 1).toString().padStart(4, '0'))
            UpdateFiles.move(existing.content, content)
            check(existing.intermediate.delete()); Os.symlink(content.path, existing.intermediate.path)
            existing.content = content; existing.count++; existing
        } else {
            check(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS))
            val rootfs = File(tree, "rootfs"); check(Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Linux 硬链接需要完整 rootfs" }
            val directory = File(rootfs, ".l2s"); UpdateFiles.directory(directory)
            val intermediate = File(directory, ".l2s.archive-${UUID.randomUUID()}")
            val content = File(intermediate.path + ".0002")
            UpdateFiles.move(original, content); Os.symlink(content.path, intermediate.path); Os.symlink(intermediate.path, original.path)
            Group(intermediate, content, 2).also { groups[original.path] = it }
        }
        Os.symlink(group.intermediate.path, alias.path); groups[alias.path] = group
        UpdateFiles.sync(group.intermediate.parentFile!!); UpdateFiles.sync(alias.parentFile!!)
    }
    fun relocated(value: String, source: String, destination: File, roots: Set<String>): String {
        if (value == source) return destination.path
        if (!value.startsWith("$source/")) return value
        val relative = value.removePrefix("$source/")
        val parts = relative.split('/')
        if (parts.first() !in roots || parts.any { it.isEmpty() || it == "." || it == ".." }) return value
        return File(destination, relative).path
    }
    /** Rebase once more from staged paths to final private paths before a confirmed directory switch. */
    fun rebase(stagedTree: File, finalDirectory: File, roots: Set<String>, checkpoint: () -> Unit = {}) {
        check(Files.isDirectory(stagedTree.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(roots.all { it in setOf("rootfs", "workspace", "home", "opencode-state", "desktop-state", "opencode-runtimes", "runtime-config") })
        val tree = stagedTree.canonicalFile; val destination = finalDirectory.canonicalFile
        for (root in roots) ArchiveTraversal.walk(File(tree, root).toPath()) { path, attributes ->
                checkpoint()
                if (attributes.isSymbolicLink) {
                    val original = Os.readlink(path.toString()); val changed = relocated(original, tree.path, destination, roots)
                    if (changed != original) {
                        check(changed.toByteArray(Charsets.UTF_8).size <= 4096)
                        Files.delete(path); Os.symlink(changed, path.toString())
                        check(io.github.opencodeandroid.runtime.NativeHost.setSymlinkModifiedTime(path.toString().toByteArray(Charsets.UTF_8), attributes.lastModifiedTime().toMillis()) == 0)
                    }
                }
        }
    }
}
