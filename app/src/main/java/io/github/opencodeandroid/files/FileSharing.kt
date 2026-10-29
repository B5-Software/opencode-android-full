package io.github.opencodeandroid.files

import io.github.opencodeandroid.i18n.nativeMessage

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import io.github.opencodeandroid.runtime.RuntimePaths
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale
import java.util.UUID

/** Grants read access to an immutable copy of one selected project file. */
object FileSharing {
    fun snapshot(context: Context, relative: String): Uri {
        val paths = RuntimePaths(context)
        val repository = ProjectFiles(paths.workspace, File(paths.backups, "project-trash"))
        val root = File(context.cacheDir, "shared-files")
        check(!Files.isSymbolicLink(root.toPath()))
        check(root.isDirectory || root.mkdir())
        check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        // Expire only our UUID containers. Active shares retain their copy for a day.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        root.listFiles()?.forEach { directory ->
            if (!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory && directory.lastModified() < cutoff && runCatching { UUID.fromString(directory.name) }.isSuccess) {
                Files.walk(directory.toPath()).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
        val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        val name = relative.substringAfterLast('/').also(repository::validName)
        val file = File(directory, name)
        try {
            Files.newOutputStream(file.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { repository.export(relative, it) }
            return FileProvider.getUriForFile(context, context.packageName + ".files", file)
        } catch (failure: Exception) { file.delete(); directory.delete(); throw failure }
    }
    fun intent(context: Context, uri: Uri, view: Boolean = false): Intent {
        val name = uri.lastPathSegment.orEmpty()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
        val action = Intent(if (view) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
            if (view) setDataAndType(uri, mime) else { type = mime; putExtra(Intent.EXTRA_STREAM, uri) }
            clipData = ClipData.newUri(context.contentResolver, name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(action, context.nativeMessage(if (view) "打开文件" else "分享文件")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
