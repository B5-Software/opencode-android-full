package io.github.opencodeandroid.files

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.RuntimePaths
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class FileSharingTest {
    @Test fun externalUidReadsImmutableSnapshotButCannotWriteOrEscapeProviderRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = RuntimePaths(context).apply { prepare() }
        val name = ".share-test-${UUID.randomUUID()}.txt"
        val source = File(paths.workspace, name).apply { writeText("SNAPSHOT") }
        var uri: Uri? = null
        try {
            uri = FileSharing.snapshot(context, name)
            source.writeText("CHANGED AFTER SHARING")
            val chooser = FileSharing.intent(context, uri)
            @Suppress("DEPRECATION") val intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("text/plain", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            context.grantUriPermission("io.github.opencodeandroid.test", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val response = context.contentResolver.call(Uri.parse("content://io.github.opencodeandroid.test.control"), "readSharedFixture", uri.toString(), null)!!
            assertEquals("SNAPSHOT", response.getString("text"))
            assertTrue(response.getBoolean("writeDenied"))
            assertTrue(runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", File(paths.state, "private-test")) }.isFailure)
            val attack = Uri.parse("content://${context.packageName}.files/shared/../../../../files/opencode-state/private-test")
            assertTrue(runCatching { context.contentResolver.openInputStream(attack)?.close() }.isFailure)
        } finally {
            uri?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            source.delete()
        }
    }
}
