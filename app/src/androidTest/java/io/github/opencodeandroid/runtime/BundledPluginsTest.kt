package io.github.opencodeandroid.runtime

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BundledPluginsTest {
    @Test fun signedPluginInstallUpdatesOnlyOwnedFilesAndRejectsSymlinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = File(context.cacheDir.canonicalFile, "plugins-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val config = File(parent, "config")
            BundledPlugins.install(context.assets, config)
            val plugin = File(config, "plugins/opencode-android-lsp")
            val source = File(plugin, "client.mjs").readText()
            assertTrue(source.contains("LspClient"))
            File(plugin, "client.mjs").writeText("old owned source")
            BundledPlugins.install(context.assets, config)
            assertEquals(source, File(plugin, "client.mjs").readText())
            val outside = File(parent, "preserve.txt").apply { writeText("PRESERVE") }
            val sidecar = File(plugin, "client.mjs.new")
            Os.symlink(outside.path, sidecar.path)
            try { assertThrows(IllegalStateException::class.java) { BundledPlugins.install(context.assets, config) } }
            finally { sidecar.delete() }
            assertEquals("PRESERVE", outside.readText())
            val other = File(parent, "other").apply { check(mkdir()) }
            Os.symlink(other.path, File(parent, "linked-config").path)
            try { assertThrows(IllegalStateException::class.java) { BundledPlugins.install(context.assets, File(parent, "linked-config")) } }
            finally { File(parent, "linked-config").delete() }
            assertTrue(other.listFiles().isNullOrEmpty())
        } finally { parent.deleteRecursively() }
    }

    @Test fun existingUserPluginIsPreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = File(context.cacheDir.canonicalFile, "plugins-user-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val plugin = File(config, "plugins/opencode-android-lsp").apply { check(mkdirs()) }
            val source = File(plugin, "index.mjs").apply { writeText("USER_PLUGIN") }
            assertThrows(IllegalStateException::class.java) { BundledPlugins.install(context.assets, config) }
            assertEquals("USER_PLUGIN", source.readText())
        } finally { config.deleteRecursively() }
    }
}
