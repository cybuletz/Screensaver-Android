package com.example.screensaver

import android.content.Context
import androidx.preference.PreferenceManager
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val sharedPreferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun getServerUrl(): String {
        return prefs.getString("server_url", "http://localhost:3000") ?: "http://localhost:3000"
    }

    fun getUsername(): String {
        return prefs.getString("username", "") ?: ""
    }

    fun getRefreshInterval(): Long {
        return prefs.getString("refresh_interval", "300")?.toLongOrNull() ?: 300L
    }

    fun isAutoStartEnabled(): Boolean {
        return prefs.getBoolean("auto_start", true)
    }

    fun isKioskModeEnabled(): Boolean {
        return sharedPreferences.getBoolean("kiosk_mode_enabled", false)
    }

    fun setKioskModeEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("kiosk_mode_enabled", enabled).apply()
    }

    fun getKioskSettingsTimeout(): Int {
        return sharedPreferences.getString("kiosk_settings_timeout", "5")?.toIntOrNull() ?: 5
    }
}