package com.example.state

import android.content.Context

class PreferencesManager(context: Context) {
    private val prefs = context.getSharedPreferences("ravana_light_prefs", Context.MODE_PRIVATE)

    var isListenerEnabled: Boolean
        get() = prefs.getBoolean("listener_enabled", true)
        set(value) {
            prefs.edit().putBoolean("listener_enabled", value).apply()
        }
}
