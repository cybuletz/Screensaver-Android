package com.example.screensaver

import android.os.Bundle
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private const val TAG = "SettingsFragment"

        fun newInstance(): SettingsFragment {
            return SettingsFragment()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Setup username preference
        findPreference<EditTextPreference>("username")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val username = newValue as String
                summary = if (username.isNotEmpty()) username else "Enter your username"
                true
            }
        }

        // Setup server URL preference
        findPreference<EditTextPreference>("server_url")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val url = newValue as String
                summary = if (url.isNotEmpty()) url else "Enter the server URL"
                true
            }
        }

        // Setup auto start preference
        findPreference<SwitchPreferenceCompat>("auto_start")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Log.d(TAG, "Auto start ${if (enabled) "enabled" else "disabled"}")
                true
            }
        }

        // Setup refresh interval preference
        findPreference<ListPreference>("refresh_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val interval = newValue as String
                val entry = entries[findIndexOfValue(interval)]
                summary = "Current interval: $entry"
                Log.d(TAG, "Refresh interval changed to: $entry ($interval seconds)")
                true
            }
        }

        // Load current values into summaries
        updateCurrentValues()
    }

    private fun updateCurrentValues() {
        // Update username summary
        findPreference<EditTextPreference>("username")?.let { pref ->
            pref.summary = pref.text?.takeIf { it.isNotEmpty() } ?: "Enter your username"
        }

        // Update server URL summary
        findPreference<EditTextPreference>("server_url")?.let { pref ->
            pref.summary = pref.text?.takeIf { it.isNotEmpty() } ?: "Enter the server URL"
        }

        // Update refresh interval summary
        findPreference<ListPreference>("refresh_interval")?.let { pref ->
            val currentValue = pref.value
            val entry = pref.entries[pref.findIndexOfValue(currentValue)]
            pref.summary = "Current interval: $entry"
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "username" -> {
                Log.d(TAG, "Username preference clicked")
                return true
            }
            "server_url" -> {
                Log.d(TAG, "Server URL preference clicked")
                return true
            }
            "auto_start" -> {
                Log.d(TAG, "Auto start preference clicked")
                return true
            }
            "refresh_interval" -> {
                Log.d(TAG, "Refresh interval preference clicked")
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onResume() {
        super.onResume()
        // Refresh preference values when fragment resumes
        updateCurrentValues()
    }

    // Helper function to safely get a preference value with default
    private fun getPreferenceString(key: String, defaultValue: String): String {
        return preferenceManager.sharedPreferences?.getString(key, defaultValue) ?: defaultValue
    }

    private fun getPreferenceBoolean(key: String, defaultValue: Boolean): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean(key, defaultValue) ?: defaultValue
    }

    // Utility functions to get specific preference values
    fun getUsername(): String = getPreferenceString("username", "")

    fun getServerUrl(): String = getPreferenceString("server_url", "http://localhost:3000")

    fun getAutoStart(): Boolean = getPreferenceBoolean("auto_start", true)

    fun getRefreshInterval(): Int = getPreferenceString("refresh_interval", "300").toInt()
}