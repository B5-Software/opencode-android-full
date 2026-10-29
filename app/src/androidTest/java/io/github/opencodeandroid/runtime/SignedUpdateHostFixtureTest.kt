package io.github.opencodeandroid.runtime

import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.SignedOpenCodeUpdate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class SignedUpdateHostFixtureTest {
    @Test fun actualApkSignerAuthenticatesAndStagesTheFullOfficialPair() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeHostSignedUpdate") == "true")
        val context = instrumentation.targetContext
        val file = File(context.getExternalFilesDir(null), "updates/native-signed.ocaupdate")
        assertTrue("Push the host-signed fixture before running this explicit acceptance", file.isFile)
        val staged = File(context.cacheDir, "update-stage-${UUID.randomUUID()}.bin")
        try {
            val verifier = SignedOpenCodeUpdate(context)
            val manifest = verifier.inspect(file)
            val bundled = JSONObject(context.assets.open("runtime/pack.json").bufferedReader().use { it.readText() }).getJSONObject("opencode")
            assertEquals(bundled.getString("sha256"), manifest.binarySha256)
            assertEquals(bundled.getString("version"), manifest.version)
            staged.outputStream().use { output -> assertEquals(manifest, verifier.extract(file, manifest.manifestSha256, output)) }
            assertEquals(manifest.binarySize, staged.length())
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "\nPASS actual installed APK certificate authenticates the complete official pair; raw/gzip SHA, exact lengths, glibc ARM64 and 16 KiB ELF checked. Staged bytes only, no activation or user-state replacement.\n") })
        } finally {
            check(staged.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            staged.delete(); file.delete()
        }
    }
}
