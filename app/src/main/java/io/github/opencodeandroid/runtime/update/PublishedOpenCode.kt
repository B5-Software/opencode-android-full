package io.github.opencodeandroid.runtime.update

import android.util.AtomicFile
import io.github.opencodeandroid.runtime.RuntimePaths
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/** Small publisher-signed provenance survives Host APK upgrades and portable backups. */
internal class PublishedOpenCode(private val paths: RuntimePaths,
    verifierOverride: SignedOpenCodeUpdate? = null) {
    private val verifier by lazy { verifierOverride ?: SignedOpenCodeUpdate(paths.applicationContext) }
    private val magic = "OCPRF001".toByteArray(Charsets.US_ASCII)
    fun bundled(): OpenCodeUpdateProof? {
        val names = paths.applicationContext.assets.list("runtime")!!.toSet()
        if ("opencode-publisher.json" !in names && "opencode-publisher.sig" !in names) return null
        fun bytes(name: String, maximum: Int): ByteArray = paths.applicationContext.assets.open("runtime/$name").use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size() + n <= maximum); out.write(buffer, 0, n) }; out.toByteArray()
        }
        return OpenCodeUpdateProof(bytes("opencode-publisher.json", 16384), bytes("opencode-publisher.sig", 8192)).also(verifier::verifyProof)
    }
    fun stored(root: File = paths.rootfs): OpenCodeUpdateProof? {
        val file = File(root, ".opencode-publisher-proof")
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        return AtomicFile(file).openRead().use { input ->
            val data = DataInputStream(input)
            check(ByteArray(8).also(data::readFully).contentEquals(magic))
            val manifestSize = data.readInt(); val signatureSize = data.readInt()
            check(manifestSize in 1..16384 && signatureSize in 1..8192)
            OpenCodeUpdateProof(ByteArray(manifestSize).also(data::readFully), ByteArray(signatureSize).also(data::readFully))
                .also { check(data.read() == -1); verifier.verifyProof(it) }
        }
    }
    fun store(root: File, proof: OpenCodeUpdateProof) {
        verifier.verifyProof(proof)
        val file = File(root, ".opencode-publisher-proof")
        check(!java.nio.file.Files.isSymbolicLink(file.toPath()))
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try {
            DataOutputStream(output).apply { write(magic); writeInt(proof.manifest.size); writeInt(proof.signature.size); write(proof.manifest); write(proof.signature); flush() }
            atomic.finishWrite(output); android.system.Os.chmod(file.path, 384); UpdateFiles.sync(root)
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    fun metadata(proof: OpenCodeUpdateProof) = verifier.verifyProof(proof)
    fun verify(binary: File, proof: OpenCodeUpdateProof): VerifiedOpenCodeUpdate = metadata(proof).also {
        check(binary.length() == it.binarySize && UpdateFiles.sha(binary) == it.binarySha256) { "官方程序与发布者证明不匹配" }
    }
}
