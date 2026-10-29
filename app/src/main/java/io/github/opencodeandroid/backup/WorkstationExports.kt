package io.github.opencodeandroid.backup

import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class WorkstationExports(private val paths: RuntimePaths) {
    private val marker get() = File(paths.files, "maintenance/ready-workstation-export.json")
    private val identity = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun directory(id: String): File {
        check(identity.matches(id)); val file = File(paths.backups, "workstation-export-$id")
        check(java.nio.file.Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)); return file
    }
    fun create(): Pair<String, File> {
        paths.prepare(); UpdateFiles.directory(File(paths.files, "maintenance"))
        val id = UUID.randomUUID().toString()
        val directory = UpdateFiles.directory(File(paths.backups, "workstation-export-$id"))
        return id to directory
    }
    fun ready(): WorkstationExport? {
        if (!UpdateFiles.exists(marker) && !File(marker.path + ".bak").exists()) return null
        return BackupJson.read(marker).let {
            check(it.getInt("format") == 1)
            WorkstationExport(it.getString("id"), it.getLong("bytes"), it.getLong("created"))
        }.also { check(it.bytes > 0 && it.created >= 0 && file(it).length() == it.bytes) }
    }
    fun file(info: WorkstationExport) = File(directory(info.id), "workstation.ocawork").also(UpdateFiles::regular)
    fun publish(id: String): WorkstationExport {
        val file = File(directory(id), "workstation.ocawork"); UpdateFiles.regular(file); check(file.length() > 0)
        val info = WorkstationExport(id, file.length(), System.currentTimeMillis())
        UpdateFiles.json(marker, JSONObject().put("format", 1).put("id", id).put("bytes", info.bytes).put("created", info.created))
        return info
    }
    fun discard(info: WorkstationExport) {
        val target = file(info); check(ready()?.id == info.id); check(target.delete())
        UpdateFiles.sync(target.parentFile!!); UpdateFiles.removeJson(marker)
        // Only this generated encrypted file is removed. Other private backups are untouched.
        target.parentFile!!.delete()
    }
}
