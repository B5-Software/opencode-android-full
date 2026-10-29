package io.github.opencodeandroid

import android.content.SharedPreferences

internal class NavigationAppearanceStore(private val preferences: SharedPreferences) {
    fun opacity() = runCatching { normalize(preferences.getFloat("orb-opacity", DEFAULT_OPACITY)) }.getOrDefault(DEFAULT_OPACITY)
    fun opacity(value: Float) { preferences.edit().putFloat("orb-opacity", normalize(value)).apply() }
    companion object {
        const val DEFAULT_OPACITY = .9f
        fun normalize(value: Float) = if (value.isFinite()) value.coerceIn(.2f, 1f) else DEFAULT_OPACITY
    }
}
