package io.github.opencodeandroid.runtime

import android.content.res.AssetManager
import android.system.Os
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Signed APK sources, installed into upstream's ordinary plugin discovery directory. */
object BundledPlugins {
    private const val NAME = "opencode-android-lsp"
    private val sources = listOf("package.json", "index.mjs", "client.mjs", "config.mjs", "access.mjs")

    fun install(assets: AssetManager, configDirectory: File) {
        directory(configDirectory)
        val plugins = File(configDirectory, "plugins").also(::directory)
        val target = File(plugins, NAME)
        val exists = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (exists) {
            check(!Files.isSymbolicLink(target.toPath()) && target.isDirectory) { "Bundled LSP plugin path is not a directory" }
            val marker = File(target, ".android-managed")
            check(!Files.isSymbolicLink(marker.toPath()) && marker.isFile && marker.readText() == NAME) {
                "LSP plugin directory belongs to an existing user plugin; preserved"
            }
        } else {
            check(target.mkdir())
            File(target, ".android-managed").writeText(NAME)
        }
        for (name in sources) {
            val file = File(target, name)
            for (candidate in listOf(file, File(target, "$name.new"), File(target, "$name.bak"))) {
                check(!Files.isSymbolicLink(candidate.toPath()) && (!candidate.exists() || candidate.isFile)) { "Unsafe managed plugin file: $name" }
            }
            val bytes = assets.open("plugins/$NAME/$name").use { it.readBytes() }
            if (file.isFile && file.readBytes().contentEquals(bytes)) continue
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
            Os.chmod(file.path, 384)
        }
        check(JSONObject(File(target, "package.json").readText()).getString("name") == NAME)
    }

    private fun directory(file: File) {
        // Check every existing component: app-private configuration can also contain Linux-created links.
        var current: File? = file.absoluteFile
        while (current != null) {
            check(!Files.isSymbolicLink(current.toPath())) { "Managed plugin directory cannot contain symbolic links" }
            current = current.parentFile
        }
        check(file.isDirectory || file.mkdirs()) { "Cannot create plugin configuration directory" }
    }
}
