package com.example.screensaver

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // Update summaries when values change
        val username = findPreference<EditTextPreference>("username")
        username?.setOnPreferenceChangeListener { preference, newValue ->
            preference.summary = newValue.toString()
            refreshWebView()
            true
        }

        val serverUrl = findPreference<EditTextPreference>("server_url")
        serverUrl?.setOnPreferenceChangeListener { preference, newValue ->
            preference.summary = newValue.toString()
            refreshWebView()
            true
        }

        val refreshInterval = findPreference<ListPreference>("refresh_interval")
        refreshInterval?.setOnPreferenceChangeListener { preference, newValue ->
            val index = refreshInterval.findIndexOfValue(newValue.toString())
            val entry = refreshInterval.entries[index]
            preference.summary = "Selected: $entry"
            refreshWebView()
            true
        }
    }

    private fun refreshWebView() {
        // Find the WebViewFragment and trigger a reload
        (activity?.supportFragmentManager?.findFragmentById(R.id.fragment_container) as? WebViewFragment)?.let {
            activity?.supportFragmentManager
                ?.beginTransaction()
                ?.replace(R.id.fragment_container, WebViewFragment.newInstance())
                ?.commit()
        }
    }
}