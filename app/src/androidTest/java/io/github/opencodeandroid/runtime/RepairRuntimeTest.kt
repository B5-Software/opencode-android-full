package io.github.opencodeandroid.runtime
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
class RepairRuntimeTest {
    @Test fun restoreIncompleteRuntimeWithoutRemovingUserData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val paths = RuntimePaths(context)
        if (!paths.installed && paths.rootfs.isDirectory) RootfsInstaller(context, paths).repairBundled(::println)
        assertTrue(paths.installed)
        assertTrue(java.io.File(paths.rootfs, "usr/bin/curl").isFile)
        assertTrue(java.io.File(paths.rootfs, "usr/local/bin/opencode").length() > 200_000_000)
    }
}
