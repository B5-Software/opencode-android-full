package io.github.opencodeandroid.backup

import android.system.Os
import io.github.opencodeandroid.runtime.RuntimeCoordination
import io.github.opencodeandroid.runtime.RuntimePaths
import io.github.opencodeandroid.runtime.update.UpdateFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

internal interface RestoreSettings {
    fun snapshot(): JSONObject
    fun validate(value: JSONObject)
    fun restore(value: JSONObject)
}

/** Rename-only recovery lives outside every root being replaced. Old workstation data is retained. */
internal class WorkstationRestore(
    private val paths: RuntimePaths,
    private val settings: RestoreSettings,
    private val checkpoint: (String) -> Unit = {},
) {
    private val maintenance get() = File(paths.files, "maintenance")
    private val journal get() = File(maintenance, "workstation-restore.json")
    private val latest get() = File(maintenance, "last-workstation-restore.json")
    private val identity = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun pending() = WorkstationRoots.pending(paths)

    fun createStage(): File {
        check(!pending()) { "请先恢复未完成的环境操作" }
        paths.prepare(); UpdateFiles.directory(maintenance)
        val transaction = UpdateFiles.directory(File(paths.backups, "workstation-restore-${UUID.randomUUID()}"))
        return UpdateFiles.directory(File(transaction, "staged"))
    }

    private fun transaction(id: String): File {
        check(identity.matches(id)) { "恢复记录无效" }
        val result = File(paths.backups, "workstation-restore-$id")
        check(Files.isDirectory(result.toPath(), LinkOption.NOFOLLOW_LINKS)) { "恢复目录缺失" }
        return result
    }
    private fun transactionFor(stage: File): Pair<String, File> {
        check(stage.name == "staged" && Files.isDirectory(stage.toPath(), LinkOption.NOFOLLOW_LINKS))
        val parent = stage.parentFile!!.canonicalFile
        check(parent.parentFile == paths.backups && parent.name.startsWith("workstation-restore-"))
        val id = parent.name.removePrefix("workstation-restore-")
        check(transaction(id) == parent && stage.canonicalFile == File(parent, "staged"))
        return id to parent
    }
    private fun step(lease: RuntimeCoordination.MaintenanceLease, name: String) { lease.checkCurrent(); checkpoint(name) }
    private fun writeJournal(id: String, operation: String, phase: String) = UpdateFiles.json(journal,
        JSONObject().put("format", 1).put("id", id).put("operation", operation).put("phase", phase))
    private fun read(file: File): JSONObject = BackupJson.read(file).also {
        BackupJson.check(it.toString()); check(it.getInt("format") == 1)
    }
    private fun plan(tx: File): JSONObject = read(File(tx, "transaction.json")).also { value ->
        val rows = value.getJSONArray("roots")
        check(rows.length() == WorkstationRoots.names.size)
        for ((index, name) in WorkstationRoots.names.withIndex()) {
            val row = rows.getJSONObject(index); check(row.getString("name") == name)
            row.getBoolean("old"); row.getBoolean("new")
        }
        settings.validate(value.getJSONObject("oldSettings")); settings.validate(value.getJSONObject("newSettings"))
        if (value.has("previous")) check(identity.matches(value.getString("previous")))
    }
    private fun rows(plan: JSONObject) = (0 until plan.getJSONArray("roots").length()).map { plan.getJSONArray("roots").getJSONObject(it) }
    private fun currentLast(): String? = if (UpdateFiles.exists(latest) || File(latest.path + ".bak").exists())
        read(latest).getString("id").also { check(identity.matches(it)) } else null
    fun canUndo(): Boolean = runCatching {
        val id = currentLast() ?: return@runCatching false
        plan(transaction(id)).getString("status") == "applied"
    }.getOrDefault(false)
    fun currentRestoreId(): String? = currentLast()

    fun apply(stageDirectory: File, descriptor: StagedWorkstation, lease: RuntimeCoordination.MaintenanceLease,
              validate: (File, StagedWorkstation) -> Unit, probe: () -> Unit) {
        step(lease, "preflight"); check(!pending()); UpdateFiles.directory(maintenance)
        val (id, tx) = transactionFor(stageDirectory)
        val stage = stageDirectory.canonicalFile
        check(descriptor.roots.all { it in WorkstationRoots.names })
        check(!UpdateFiles.exists(File(tx, "transaction.json")))
        for (name in WorkstationRoots.names) {
            val incoming = File(stage, name); val live = File(paths.files, name)
            check(UpdateFiles.exists(incoming) == (name in descriptor.roots))
            if (UpdateFiles.exists(incoming)) check(Files.isDirectory(incoming.toPath(), LinkOption.NOFOLLOW_LINKS))
            if (UpdateFiles.exists(live)) check(Files.isDirectory(live.toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        val newSettings = descriptor.metadata.getJSONObject("settings")
        settings.validate(newSettings); val oldSettings = settings.snapshot(); settings.validate(oldSettings)
        validate(stage, descriptor); step(lease, "validated")
        ArchiveLinks.rebase(stage, paths.files, descriptor.roots) { lease.checkCurrent() }
        val record = JSONObject().put("format", 1).put("status", "prepared")
            .put("oldSettings", oldSettings).put("newSettings", newSettings)
            .put("roots", JSONArray().apply {
                for (name in WorkstationRoots.names) put(JSONObject().put("name", name)
                    .put("old", UpdateFiles.exists(File(paths.files, name))).put("new", name in descriptor.roots))
            })
        currentLast()?.let { record.put("previous", it) }
        check(record.toString().toByteArray().size <= 64 * 1024) { "原生设置超过恢复记录限制" }
        UpdateFiles.directory(File(tx, "previous")); UpdateFiles.directory(File(tx, "failed"))
        UpdateFiles.json(File(tx, "transaction.json"), record)
        writeJournal(id, "restore", "prepared"); step(lease, "journal")
        for (row in rows(record)) {
            val name = row.getString("name"); val live = File(paths.files, name)
            if (row.getBoolean("old")) UpdateFiles.move(live, File(tx, "previous/$name"))
            step(lease, "saved:$name")
            if (row.getBoolean("new")) UpdateFiles.move(File(stage, name), live)
            step(lease, "installed:$name")
        }
        settings.restore(newSettings); step(lease, "settings")
        probe(); step(lease, "verified")
        writeJournal(id, "restore", "committed"); step(lease, "committed")
        finishRestore(id, tx, record, lease)
    }

    private fun finishRestore(id: String, tx: File, record: JSONObject, lease: RuntimeCoordination.MaintenanceLease) {
        for (row in rows(record)) check(UpdateFiles.exists(File(paths.files, row.getString("name"))) == row.getBoolean("new"))
        settings.restore(record.getJSONObject("newSettings"))
        UpdateFiles.json(latest, JSONObject().put("format", 1).put("id", id))
        record.put("status", "applied"); UpdateFiles.json(File(tx, "transaction.json"), record)
        step(lease, "before-journal-clear"); UpdateFiles.removeJson(journal)
    }

    fun recover(lease: RuntimeCoordination.MaintenanceLease) {
        lease.checkCurrent(); if (!pending()) return
        val entry = read(journal); val id = entry.getString("id"); val tx = transaction(id); val record = plan(tx)
        when (entry.getString("operation")) {
            "restore" -> when (entry.getString("phase")) {
                "committed" -> finishRestore(id, tx, record, lease)
                "prepared" -> {
                    for (row in rows(record).asReversed()) {
                        val name = row.getString("name"); val live = File(paths.files, name)
                        val old = File(tx, "previous/$name"); val failed = File(tx, "failed/$name")
                        if (UpdateFiles.exists(old)) {
                            if (UpdateFiles.exists(live)) UpdateFiles.move(live, failed)
                            step(lease, "rollback-removed:$name")
                            UpdateFiles.move(old, live); step(lease, "rollback-restored:$name")
                        } else if (!row.getBoolean("old")) {
                            if (UpdateFiles.exists(live)) UpdateFiles.move(live, failed)
                            step(lease, "rollback-removed:$name")
                        } else check(Files.isDirectory(live.toPath(), LinkOption.NOFOLLOW_LINKS)) { "旧环境目录缺失：$name" }
                    }
                    settings.restore(record.getJSONObject("oldSettings")); step(lease, "rollback-settings")
                    record.put("status", "reverted"); UpdateFiles.json(File(tx, "transaction.json"), record)
                    UpdateFiles.removeJson(journal)
                }
                else -> error("未知恢复阶段")
            }
            "undo" -> { check(entry.getString("phase") == "prepared"); finishUndo(tx, record, lease) }
            else -> error("未知恢复操作")
        }
    }

    /** This starts only after a native confirmation. Interrupted undo always completes the approved action. */
    fun undo(lease: RuntimeCoordination.MaintenanceLease, validate: (File, Set<String>) -> Unit) {
        step(lease, "undo-preflight"); check(!pending()); val id = checkNotNull(currentLast()) { "没有可还原的旧环境" }
        val tx = transaction(id); val record = plan(tx); check(record.getString("status") == "applied")
        val original = rows(record).filter { it.getBoolean("old") }.map { it.getString("name") }.toSet()
        for (name in original) check(Files.isDirectory(File(tx, "previous/$name").toPath(), LinkOption.NOFOLLOW_LINKS)) { "旧环境目录缺失：$name" }
        validate(File(tx, "previous"), original); step(lease, "undo-validated")
        UpdateFiles.directory(File(tx, "after-restore")); writeJournal(id, "undo", "prepared"); step(lease, "undo-journal")
        finishUndo(tx, record, lease)
    }
    private fun finishUndo(tx: File, record: JSONObject, lease: RuntimeCoordination.MaintenanceLease) {
        val after = File(tx, "after-restore")
        check(Files.isDirectory(after.toPath(), LinkOption.NOFOLLOW_LINKS))
        for (row in rows(record)) {
            val name = row.getString("name"); val old = File(tx, "previous/$name"); val live = File(paths.files, name)
            val retained = File(after, name)
            if (row.getBoolean("old") && !UpdateFiles.exists(old)) {
                check(Files.isDirectory(live.toPath(), LinkOption.NOFOLLOW_LINKS)) { "旧环境目录缺失：$name" }
                continue
            }
            if (UpdateFiles.exists(live)) { check(!UpdateFiles.exists(retained)); UpdateFiles.move(live, retained) }
            step(lease, "undo-saved:$name")
            if (row.getBoolean("old")) UpdateFiles.move(old, live)
            step(lease, "undo-restored:$name")
        }
        settings.restore(record.getJSONObject("oldSettings")); step(lease, "undo-settings")
        val previous = record.optString("previous").takeIf { it.isNotEmpty() }
        if (previous == null) { if (UpdateFiles.exists(latest) || File(latest.path + ".bak").exists()) UpdateFiles.removeJson(latest) }
        else UpdateFiles.json(latest, JSONObject().put("format", 1).put("id", previous))
        record.put("status", "undone"); UpdateFiles.json(File(tx, "transaction.json"), record)
        step(lease, "undo-committed"); UpdateFiles.removeJson(journal)
    }

    /** Only an unapproved private staging tree may be discarded; transaction histories are retained. */
    fun discardStage(stage: File) {
        val (_, tx) = transactionFor(stage)
        check(!UpdateFiles.exists(File(tx, "transaction.json")) && !pending())
        ArchiveTraversal.remove(tx.toPath())
        UpdateFiles.sync(paths.backups)
    }
}
