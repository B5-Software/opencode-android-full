package io.github.opencodeandroid.backup

import android.content.Context
import io.github.opencodeandroid.preview.PreviewTarget
import io.github.opencodeandroid.runtime.WorkspaceContext
import org.json.JSONObject

/** A typed allowlist deliberately excludes Android credentials and implementation preferences. */
internal class PortableSettings(private val context: Context) : RestoreSettings {
    private val stores = setOf("workspace-layout", "workspace-navigation", "webview-rendering", "workspace-context", "project-preview")
    private val size = "(?:compact|expanded|large)-(?:short|tall)"
    private val layoutMode = Regex("$size\\.mode")
    private val layoutFlag = Regex("$size\\.(?:Develop|Hybrid|Single)\\.(?:files|terminal|right)")
    private val layoutRatio = Regex("$size\\.(?:Develop|Hybrid|Single)\\.(?:files-ratio|code-ratio|terminal-ratio)")
    private fun type(store: String, key: String): String? = when (store) {
        "workspace-layout" -> when { layoutMode.matches(key) -> "s"; layoutFlag.matches(key) -> "b"; layoutRatio.matches(key) -> "f"; else -> null }
        "workspace-navigation" -> when (key) { "orb-right" -> "b"; "orb-opacity", "orb-position" -> "f"; else -> null }
        "webview-rendering" -> "b".takeIf { key == "balanced" }
        "workspace-context" -> "s".takeIf { key in setOf("directory", "session-id") }
        "project-preview" -> "i".takeIf { key == "port" }
        else -> null
    }
    private fun checkValue(store: String, key: String, value: JSONObject) {
        val expected = checkNotNull(type(store, key)) { "不支持的原生设置" }
        check(value.length() == 2 && value.getString("t") == expected)
        val raw = value.get("v")
        when (expected) {
            "b" -> check(raw is Boolean)
            "s" -> {
                check(raw is String)
                if (store == "workspace-context" && key == "session-id") check(io.github.opencodeandroid.runtime.WorkspaceSelectionStore.validSession(raw))
                else if (store == "workspace-context") WorkspaceContext.validDirectory(raw)
                else check(raw in setOf("Develop", "Hybrid", "Single"))
            }
            "i" -> { check(raw is Number); PreviewTarget.fromValue(raw) }
            "f" -> {
                check(raw is Number); val number = raw.toDouble(); check(number.isFinite())
                check(number in when {
                    store == "workspace-layout" -> .1.. .9
                    key == "orb-opacity" -> .2..1.0
                    else -> 0.0..1.0
                })
            }
        }
    }
    override fun snapshot(): JSONObject = JSONObject().put("format", 1).put("stores", JSONObject().apply {
        for (store in stores) put(store, JSONObject().apply {
            for ((key, raw) in context.getSharedPreferences(store, Context.MODE_PRIVATE).all) {
                val kind = type(store, key) ?: continue
                val value = runCatching { JSONObject().put("t", kind).put("v", raw).also { checkValue(store, key, it) } }.getOrNull() ?: continue
                put(key, value)
            }
        })
    }).also(::validate)
    override fun validate(value: JSONObject) {
        check(value.toString().toByteArray().size <= 24 * 1024); BackupJson.check(value.toString())
        check(value.getInt("format") == 1 && value.length() == 2)
        val entries = value.getJSONObject("stores"); check(entries.length() <= stores.size)
        for (store in entries.keys()) {
            check(store in stores); val values = entries.getJSONObject(store)
            check(values.length() <= 128)
            for (key in values.keys()) checkValue(store, key, values.getJSONObject(key))
        }
    }
    override fun restore(value: JSONObject) {
        validate(value); val entries = value.getJSONObject("stores")
        for (store in stores) {
            val preferences = context.getSharedPreferences(store, Context.MODE_PRIVATE)
            val edit = preferences.edit()
            for (key in preferences.all.keys) if (type(store, key) != null) edit.remove(key)
            val values = entries.optJSONObject(store) ?: JSONObject()
            for (key in values.keys()) {
                val item = values.getJSONObject(key)
                when (item.getString("t")) {
                    "b" -> edit.putBoolean(key, item.getBoolean("v"))
                    "s" -> edit.putString(key, item.getString("v"))
                    "i" -> edit.putInt(key, item.getInt("v"))
                    "f" -> edit.putFloat(key, item.getDouble("v").toFloat())
                }
            }
            check(edit.commit()) { "原生设置保存失败" }
        }
    }
}
