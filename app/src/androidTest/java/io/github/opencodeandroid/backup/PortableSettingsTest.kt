package io.github.opencodeandroid.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PortableSettingsTest {
    private fun isolated(action: (Context, PortableSettings) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "backup-settings-${UUID.randomUUID()}-"
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names += name; return target.getSharedPreferences(prefix + name, mode)
            }
        }
        try { action(context, PortableSettings(context)) }
        finally { for (name in names) { val full = prefix + name; check(full.startsWith("backup-settings-")); target.deleteSharedPreferences(full) } }
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Expected a typed allowlist rejection") } catch (expected: IllegalStateException) {} catch (expected: IllegalArgumentException) {} }

    @Test fun completeKnownPreferencesRoundTripWhileCredentialsAndUnknownKeysRemainPrivate() = isolated { context, store ->
        val navigation = context.getSharedPreferences("workspace-navigation", 0)
        navigation.edit().putFloat("orb-opacity", .45f).putBoolean("orb-right", false).putFloat("orb-position", .25f).putString("private-token", "not-exported").commit()
        val layout = context.getSharedPreferences("workspace-layout", 0)
        layout.edit().putString("expanded-tall.mode", "Hybrid").putFloat("expanded-tall.Hybrid.code-ratio", .6f).putBoolean("expanded-tall.Hybrid.terminal", true).commit()
        context.getSharedPreferences("workspace-context", 0).edit().putString("directory", "/workspace/项目-📦").putString("service-password", "not-exported-either").commit()
        context.getSharedPreferences("webview-rendering", 0).edit().putBoolean("balanced", false).commit()
        context.getSharedPreferences("project-preview", 0).edit().putInt("port", 8080).commit()
        val snapshot = store.snapshot(); assertFalse(snapshot.toString().contains("not-exported")); assertFalse(snapshot.toString().contains("private-token"))
        navigation.edit().putFloat("orb-opacity", .9f).putBoolean("orb-right", true).putString("private-token", "still-local").commit()
        layout.edit().putString("expanded-tall.mode", "Develop").putBoolean("compact-short.Single.files", true).commit()
        store.restore(snapshot)
        assertEquals(.45f, navigation.getFloat("orb-opacity", 0f)); assertFalse(navigation.getBoolean("orb-right", true))
        assertEquals("still-local", navigation.getString("private-token", null))
        assertEquals("Hybrid", layout.getString("expanded-tall.mode", null)); assertFalse(layout.contains("compact-short.Single.files"))
        assertEquals("/workspace/项目-📦", context.getSharedPreferences("workspace-context", 0).getString("directory", null))
        assertFalse(context.getSharedPreferences("webview-rendering", 0).getBoolean("balanced", true))
        assertEquals(8080, context.getSharedPreferences("project-preview", 0).getInt("port", 0))
    }
    @Test fun invalidTypesRangesAndAdditionalNamespacesAreRefusedBeforeAnyPreferenceChanges() = isolated { context, store ->
        val preferences = context.getSharedPreferences("workspace-navigation", 0)
        preferences.edit().putFloat("orb-opacity", .55f).commit()
        fun forged(namespace: String, key: String, kind: String, value: Any) = JSONObject().put("format", 1).put("stores",
            JSONObject().put(namespace, JSONObject().put(key, JSONObject().put("t", kind).put("v", value))))
        val invalid = listOf(
            forged("workspace-navigation", "orb-opacity", "f", 9), forged("workspace-navigation", "orb-opacity", "f", ".6"),
            forged("workspace-navigation", "orb-right", "b", "true"), forged("workspace-navigation", "private-token", "s", "injected"),
            forged("webview-rendering", "balanced", "s", "false"), forged("project-preview", "port", "i", 49374),
            forged("project-preview", "port", "i", 3000.5), forged("workspace-context", "directory", "s", "relative"),
            forged("workspace-layout", "large-tall.mode", "s", "unsupported"), forged("device-credentials", "token", "s", "injected"))
        for (value in invalid) { rejected { store.restore(value) }; assertEquals(.55f, preferences.getFloat("orb-opacity", 0f)) }
    }
    @Test fun corruptExistingSettingsAreSkippedRatherThanExportedAsUnusableValues() = isolated { context, store ->
        context.getSharedPreferences("workspace-navigation", 0).edit().putFloat("orb-opacity", Float.NaN).putString("orb-position", "wrong").commit()
        val values = store.snapshot().getJSONObject("stores").getJSONObject("workspace-navigation")
        assertFalse(values.has("orb-opacity")); assertFalse(values.has("orb-position")); store.validate(store.snapshot())
    }
}
