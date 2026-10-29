package io.github.opencodeandroid.backup

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.update.VerifiedOpenCodeUpdate
import io.github.opencodeandroid.runtime.update.OpenCodeUpdates
import io.github.opencodeandroid.runtime.update.PublishedOpenCode
import io.github.opencodeandroid.runtime.update.UpdateFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal object WorkstationValidation {
    data class Inspection(val core: VerifiedOpenCodeUpdate?, val installed: Boolean, val desktopCompatible: Boolean)
    fun inspect(paths: RuntimePaths, tree: File, descriptor: StagedWorkstation): Inspection {
        val metadata = descriptor.metadata
        check(metadata.getInt("format") == 1 && metadata.getString("package") == paths.applicationContext.packageName) { "备份不属于此应用" }
        check(metadata.getString("abi") == Build.SUPPORTED_ABIS.first()) { "备份的处理器架构不匹配" }
        val host = paths.applicationContext.packageManager.getPackageInfo(paths.applicationContext.packageName, 0).longVersionCode
        check(metadata.getLong("hostVersionCode") <= host) { "请先将 Android Host 更新到备份所用版本" }
        check(metadata.getLong("createdUtcMillis") >= 0)
        PortableSettings(paths.applicationContext).validate(metadata.getJSONObject("settings"))
        return environment(paths, tree, descriptor.roots)
    }
    fun environment(paths: RuntimePaths, treeDirectory: File, roots: Set<String>): Inspection {
        check(Files.isDirectory(treeDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
        check(roots.all { it in WorkstationRoots.names })
        val tree = treeDirectory.canonicalFile
        for (name in roots) check(Files.isDirectory(File(tree, name).toPath(), LinkOption.NOFOLLOW_LINKS)) { "备份目录不完整" }
        val source = RuntimePaths(object : ContextWrapper(paths.applicationContext) { override fun getFilesDir() = tree })
        val marker = File(source.rootfs, ".installed-pack")
        val installed = "rootfs" in roots && Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)
        val core = if (installed) {
            val active = OpenCodeUpdates(source).active(true)
            if (active != null) active.second else {
                val published = PublishedOpenCode(source)
                val proof = published.stored() ?: checkNotNull(published.bundled()) { "旧备份缺少可验证的 OpenCode 发布者证明，请用当前 Host 先启动原环境再备份" }
                published.verify(File(source.rootfs, "usr/local/bin/opencode"), proof)
            }
        } else null
        val compatibility = File(source.desktop, "chromium-compat-enabled")
        return Inspection(core, installed, UpdateFiles.exists(compatibility))
    }
}
