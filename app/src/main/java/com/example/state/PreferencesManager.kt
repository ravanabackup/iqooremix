package com.example.state

import android.content.Context

class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("ravana_light_prefs", Context.MODE_PRIVATE)

    var isListenerEnabled: Boolean
        get() = prefs.getBoolean("listener_enabled", true)
        set(value) {
            prefs.edit().putBoolean("listener_enabled", value).apply()
        }

    var isRhythmSyncEnabled: Boolean
        get() = prefs.getBoolean("rhythm_sync_enabled", true)
        set(value) {
            prefs.edit().putBoolean("rhythm_sync_enabled", value).apply()
        }

    var rhythmSyncStyle: String
        get() = prefs.getString("rhythm_sync_style", "neon_glow") ?: "neon_glow"
        set(value) {
            prefs.edit().putString("rhythm_sync_style", value).apply()
        }

    var rhythmColorMode: String
        get() = prefs.getString("rhythm_color_mode", "album_art") ?: "album_art"
        set(value) {
            prefs.edit().putString("rhythm_color_mode", value).apply()
        }

    var rhythmSensitivity: Float
        get() = prefs.getFloat("rhythm_sensitivity", 1.0f)
        set(value) {
            prefs.edit().putFloat("rhythm_sensitivity", value).apply()
        }

    var rhythmBrightness: Float
        get() = prefs.getFloat("rhythm_brightness", 0.8f)
        set(value) {
            prefs.edit().putFloat("rhythm_brightness", value).apply()
        }

    var activeMediaPackage: String
        get() = prefs.getString("active_media_package", "all") ?: "all"
        set(value) {
            prefs.edit().putString("active_media_package", value).apply()
        }

    var appThemeMode: String
        get() = prefs.getString("app_theme_mode", "system") ?: "system"
        set(value) {
            prefs.edit().putString("app_theme_mode", value).apply()
        }
}
