package io.github.opencodeandroid.runtime.update

import android.system.Os
import io.github.opencodeandroid.runtime.RuntimeCoordination
import io.github.opencodeandroid.runtime.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Private executable selection. The bundled Debian executable is never overwritten. */
internal class OpenCodeUpdates(
    private val paths: RuntimePaths,
    private val verifierOverride: SignedOpenCodeUpdate? = null,
    private val baselineProofOverride: (() -> OpenCodeUpdateProof?)? = null,
    private val checkpoint: (String) -> Unit = {},
) {
    private val verifier by lazy { verifierOverride ?: SignedOpenCodeUpdate(paths.applicationContext) }
    private val selection get() = File(paths.config, "opencode-active.json")
    private val journal get() = File(paths.config, "opencode-update-journal.json")
    private val lastBackup get() = File(paths.config, "opencode-update-backup.json")
    private fun id(value: String) = value.also { check(it.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) { "更新记录标识无效" } }
    private fun optional(json: JSONObject, key: String) = if (json.isNull(key)) null else id(json.getString(key))
    private fun version(identifier: String) = File(paths.openCodeVersions, id(identifier)).also { check(it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath())) }
    private fun transaction(identifier: String) = File(paths.backups, "opencode-update-${id(identifier)}").also { check(it.isDirectory && !java.nio.file.Files.isSymbolicLink(it.toPath())) }
    private fun atomicExists(file: File) = file.exists() || File(file.path + ".bak").exists()
    private fun selectedId(): String? = if (atomicExists(selection)) id(UpdateFiles.readJson(selection).getString("id")) else null
    private fun previousBackup(): String? = if (atomicExists(lastBackup)) id(UpdateFiles.readJson(lastBackup).getString("id")) else null
    private fun select(identifier: String?) { if (identifier == null) UpdateFiles.removeJson(selection) else UpdateFiles.json(selection, JSONObject().put("id", id(identifier))) }
    private fun backup(identifier: String?) { if (identifier == null) UpdateFiles.removeJson(lastBackup) else UpdateFiles.json(lastBackup, JSONObject().put("id", id(identifier))) }
    private fun proof(directory: File): OpenCodeUpdateProof {
        fun read(name: String, maximum: Long): ByteArray {
            val file = File(directory, name); UpdateFiles.regular(file); check(file.length() in 1..maximum); return file.readBytes()
        }
        return OpenCodeUpdateProof(read("manifest.json", 16 * 1024), read("manifest.sig", 8 * 1024))
    }
    fun active(verifyBinary: Boolean = false): Pair<File, VerifiedOpenCodeUpdate>? {
        val directory = selectedId()?.let(::version) ?: return null
        val metadata = verifier.verifyProof(proof(directory))
        val binary = File(directory, "opencode"); UpdateFiles.regular(binary)
        check(binary.length() == metadata.binarySize) { "已安装的独立 OpenCode 大小不匹配" }
        if (verifyBinary) check(UpdateFiles.sha(binary) == metadata.binarySha256) { "已安装的独立 OpenCode 摘要不匹配，请恢复上一版本" }
        return binary to metadata
    }
    fun hasRollback(): Boolean = previousBackup()?.let { UpdateFiles.exists(File(transaction(it), "previous-state")) } == true
    fun browserKey(): String {
        selectedId()?.let { return "runtime-$it" }
        val published = PublishedOpenCode(paths, verifierOverride)
        published.stored()?.let { return "bundled-${published.metadata(it).binarySha256}" }
        return paths.applicationContext.assets.open("runtime/pack.json").bufferedReader().use { "bundled-${JSONObject(it.readText()).getJSONObject("opencode").getString("sha256")}" }
    }
    private fun retainBaseline(): String? {
        val published = PublishedOpenCode(paths, verifier)
        val proof = if (baselineProofOverride != null) baselineProofOverride.invoke() else published.stored() ?: published.bundled()
        if (proof == null) return null // Older APKs without provenance retain the guarded legacy fallback.
        val metadata = published.metadata(proof)
        val source = File(paths.rootfs, "usr/local/bin/opencode"); UpdateFiles.regular(source)
        val identifier = UUID.randomUUID().toString(); val directory = UpdateFiles.directory(File(paths.openCodeVersions, identifier))
        UpdateFiles.bytes(File(directory, "manifest.json"), proof.manifest); UpdateFiles.bytes(File(directory, "manifest.sig"), proof.signature)
        val binary = File(directory, "opencode")
        val fd = Os.open(binary.path, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW, 384)
        var count = 0L; val hash = java.security.MessageDigest.getInstance("SHA-256")
        try {
            java.nio.file.Files.newInputStream(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input -> FileOutputStream(fd).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; count += n; check(count <= metadata.binarySize); checkpoint("retaining-baseline"); hash.update(buffer, 0, n); output.write(buffer, 0, n) }; output.fd.sync()
            } }
        } finally { Os.close(fd) }
        check(count == metadata.binarySize && hash.digest().joinToString("") { "%02x".format(it) } == metadata.binarySha256) { "原程序缺少匹配的发布者证明，旧状态保持不变" }
        Os.chmod(binary.path, 320); UpdateFiles.sync(directory); UpdateFiles.sync(paths.openCodeVersions)
        return identifier
    }
    fun recoveryPending() = journal.exists() || File(journal.path + ".bak").exists()

    /** Call before claiming a normal service. Live maintenance owns and resolves its own journal. */
    fun recoverBeforeStart(): Boolean {
        if (!recoveryPending()) return false
        RuntimeCoordination.claimMaintenance().use { recover(it) }
        return true
    }

    fun install(bundle: File, confirmedManifest: String, lease: RuntimeCoordination.MaintenanceLease,
                probe: (VerifiedOpenCodeUpdate) -> Unit): VerifiedOpenCodeUpdate {
        lease.checkCurrent(); check(!recoveryPending()) { "请先恢复上次中断的更新" }; check(paths.installed)
        val authenticated = verifier.proof(bundle)
        val metadata = verifier.verifyProof(authenticated)
        check(metadata.manifestSha256 == confirmedManifest) { "更新包在确认后发生变化" }
        check(paths.files.usableSpace > metadata.binarySize + 64L * 1024 * 1024) { "空间不足，原版本保持不变" }
        val identifier = UUID.randomUUID().toString()
        val directory = UpdateFiles.directory(File(paths.openCodeVersions, identifier))
        UpdateFiles.bytes(File(directory, "manifest.json"), authenticated.manifest)
        UpdateFiles.bytes(File(directory, "manifest.sig"), authenticated.signature)
        val binary = File(directory, "opencode")
        val fd = Os.open(binary.path, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW, 384)
        try { FileOutputStream(fd).use { output -> verifier.extract(bundle, confirmedManifest, output) { lease.checkCurrent(); checkpoint("extracting") }; output.fd.sync() } } finally { Os.close(fd) }
        Os.chmod(binary.path, 320); UpdateFiles.sync(directory); UpdateFiles.sync(paths.openCodeVersions)
        checkpoint("extracted")
        val originalSelection = selectedId()
        val previousProgram = originalSelection ?: retainBaseline()
        val transaction = UpdateFiles.directory(File(paths.backups, "opencode-update-$identifier"))
        UpdateFiles.snapshot(paths.state, File(transaction, "candidate-state")) { lease.checkCurrent(); checkpoint("snapshotting") }
        UpdateFiles.sync(transaction); UpdateFiles.sync(paths.backups)
        val record = JSONObject().put("format", 1).put("operation", "install").put("id", identifier)
            .put("previous", previousProgram ?: JSONObject.NULL).put("original", originalSelection ?: JSONObject.NULL).put("previousBackup", previousBackup() ?: JSONObject.NULL)
            .put("candidate", identifier).put("phase", "prepared")
        if (previousProgram == null) record.put("bundledSha256", UpdateFiles.sha(File(paths.rootfs, "usr/local/bin/opencode")))
            .put("bundledAssetSha256", browserKey())
        UpdateFiles.json(File(transaction, "transaction.json"), record)
        UpdateFiles.json(journal, record)
        try {
            checkpoint("prepared")
            UpdateFiles.move(paths.state, File(transaction, "previous-state")); checkpoint("old-state-moved")
            UpdateFiles.move(File(transaction, "candidate-state"), paths.state); checkpoint("new-state-moved")
            select(identifier); checkpoint("selected")
            probe(metadata); checkpoint("probe-complete")
            UpdateFiles.json(journal, record.put("phase", "committed")); checkpoint("committed")
            recover(lease)
            return metadata
        } catch (failure: Exception) {
            // Synchronous recovery also runs when the caller cancels its coroutine.
            // VM death leaves the durable journal for the next explicit start.
            try { recover(lease) } catch (recovery: Exception) { failure.addSuppressed(recovery) }
            throw failure
        }
    }

    /** Approved rollback restores the pre-update sessions; newer sessions stay in the backup. */
    fun rollback(lease: RuntimeCoordination.MaintenanceLease) {
        lease.checkCurrent(); check(!recoveryPending())
        val identifier = previousBackup() ?: error("没有可恢复的更新备份")
        val transaction = transaction(identifier)
        val original = UpdateFiles.readJson(File(transaction, "transaction.json"))
        check(selectedId() == original.getString("candidate")) { "备份与当前运行版本不匹配" }
        optional(original, "previous")?.let {
            val old = version(it); val metadata = verifier.verifyProof(proof(old)); val binary = File(old, "opencode")
            check(binary.length() == metadata.binarySize && UpdateFiles.sha(binary) == metadata.binarySha256) { "旧程序校验失败，当前会话保持不变" }
        }
        if (original.isNull("previous")) {
            val currentBundle = paths.applicationContext.assets.open("runtime/pack.json").bufferedReader().use { "bundled-${JSONObject(it.readText()).getJSONObject("opencode").getString("sha256")}" }
            check(original.optString("bundledSha256") == UpdateFiles.sha(File(paths.rootfs, "usr/local/bin/opencode")) && original.optString("bundledAssetSha256") == currentBundle) { "内置程序已被后续 APK 或 Debian 更新替换；原会话备份保留，需先恢复对应程序" }
        }
        check(File(transaction, "previous-state").isDirectory && !UpdateFiles.exists(File(transaction, "after-update-state")))
        val record = JSONObject(original.toString()).put("operation", "rollback").put("phase", "prepared")
        UpdateFiles.json(journal, record); checkpoint("rollback-prepared")
        recover(lease)
    }

    /** Rename-only recovery never deletes either state tree or any project files. */
    fun recover(lease: RuntimeCoordination.MaintenanceLease) {
        lease.checkCurrent()
        if (!recoveryPending()) return
        val record = UpdateFiles.readJson(journal)
        check(record.getInt("format") == 1 && record.getString("phase") in setOf("prepared", "committed"))
        val identifier = id(record.getString("id")); val transaction = transaction(identifier)
        val candidate = id(record.getString("candidate")); check(candidate == identifier)
        val previous = optional(record, "previous"); val previousBackup = optional(record, "previousBackup")
        val originalState = File(transaction, "previous-state")
        when (record.getString("operation")) {
            "install" -> if (record.getString("phase") == "committed") {
                check(paths.state.isDirectory && originalState.isDirectory && selectedId() == candidate)
                active(verifyBinary = true); backup(identifier)
            } else {
                if (UpdateFiles.exists(originalState)) {
                    if (UpdateFiles.exists(paths.state)) UpdateFiles.move(paths.state, File(transaction, "failed-state"))
                    UpdateFiles.move(originalState, paths.state)
                }
                check(paths.state.isDirectory)
                select(if (record.has("original")) optional(record, "original") else previous); backup(previousBackup)
            }
            "rollback" -> {
                if (UpdateFiles.exists(originalState)) {
                    if (UpdateFiles.exists(paths.state)) UpdateFiles.move(paths.state, File(transaction, "after-update-state"))
                    checkpoint("rollback-current-state-moved")
                    UpdateFiles.move(originalState, paths.state); checkpoint("rollback-old-state-restored")
                }
                check(paths.state.isDirectory)
                select(previous); checkpoint("rollback-selected"); backup(previousBackup)
            }
            else -> error("更新恢复操作无效")
        }
        UpdateFiles.removeJson(journal)
    }
}
