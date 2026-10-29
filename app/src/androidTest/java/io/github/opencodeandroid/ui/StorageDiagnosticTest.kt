package io.github.opencodeandroid.ui
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
class StorageDiagnosticTest {
    @Test fun inspectOwnedRuntimeMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.filesDir
        val inventory = File(root, "rootfs/usr/share/opencode-android/developer-packages.tsv")
        val report = "uid=${android.os.Process.myUid()} context=${context.packageName} rootfs=${File(root,"rootfs").isDirectory} roots=${File(root,"rootfs").list()?.joinToString()} installed=${File(root,"rootfs/.installed-pack").isFile} inventory=${inventory.isFile} opencode=${File(root,"rootfs/usr/local/bin/opencode").length()} bash=${File(root,"rootfs/usr/bin/bash").length()} curl=${File(root,"rootfs/usr/bin/curl").length()} backups=${File(root,"backups").listFiles()?.filter { it.name.startsWith("rootfs-") }?.map { it.name + ":" + File(it,".installed-pack").isFile }}"
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("stream", report) })
    }
}
