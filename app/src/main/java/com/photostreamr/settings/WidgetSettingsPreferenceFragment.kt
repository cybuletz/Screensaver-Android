package com.photostreamr.settings

import android.os.Bundle
import android.util.Log
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.photostreamr.R
import com.photostreamr.widgets.WidgetManager
import com.photostreamr.widgets.WidgetPreferenceDialog
import com.photostreamr.widgets.WidgetType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WidgetsSettingsPreferenceFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var widgetManager: WidgetManager

    private var initialPreferences: Bundle? = null

    companion object {
        private const val TAG = "WidgetsSettingsPreference"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.widgets_settings_preferences, rootKey)

        // Save initial preferences state for potential cancel operation
        initialPreferences = Bundle().apply {
            savePreferenceState(preferenceScreen, this)
        }

        setupPreferenceListeners()
        updateWidgetSummaries()
    }

    private fun savePreferenceState(screen: androidx.preference.PreferenceScreen, outState: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            when (preference) {
                is androidx.preference.EditTextPreference -> {
                    outState.putString(preference.key, preference.text)
                }
                is androidx.preference.SwitchPreferenceCompat -> {
                    outState.putBoolean(preference.key, preference.isChecked)
                }
                is androidx.preference.ListPreference -> {
                    outState.putString(preference.key, preference.value)
                }
                is androidx.preference.PreferenceCategory -> {
                    savePreferenceState(preference as androidx.preference.PreferenceGroup, outState)
                }
                is androidx.preference.PreferenceGroup -> {
                    savePreferenceState(preference, outState)
                }
            }
        }
    }

    private fun savePreferenceState(group: androidx.preference.PreferenceGroup, outState: Bundle) {
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            when (preference) {
                is androidx.preference.EditTextPreference -> {
                    outState.putString(preference.key, preference.text)
                }
                is androidx.preference.SwitchPreferenceCompat -> {
                    outState.putBoolean(preference.key, preference.isChecked)
                }
                is androidx.preference.ListPreference -> {
                    outState.putString(preference.key, preference.value)
                }
                is androidx.preference.PreferenceCategory -> {
                    savePreferenceState(preference as androidx.preference.PreferenceGroup, outState)
                }
                is androidx.preference.PreferenceGroup -> {
                    savePreferenceState(preference, outState)
                }
            }
        }
    }

    private fun restorePreferenceState(screen: androidx.preference.PreferenceScreen, savedState: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            when (preference) {
                is androidx.preference.EditTextPreference -> {
                    val savedValue = savedState.getString(preference.key)
                    if (savedValue != null) {
                        preference.text = savedValue
                    }
                }
                is androidx.preference.SwitchPreferenceCompat -> {
                    preference.isChecked = savedState.getBoolean(preference.key, preference.isChecked)
                }
                is androidx.preference.ListPreference -> {
                    val savedValue = savedState.getString(preference.key)
                    if (savedValue != null) {
                        preference.value = savedValue
                    }
                }
                is androidx.preference.PreferenceCategory -> {
                    restorePreferenceState(preference as androidx.preference.PreferenceGroup, savedState)
                }
                is androidx.preference.PreferenceGroup -> {
                    restorePreferenceState(preference, savedState)
                }
            }
        }
    }

    private fun restorePreferenceState(group: androidx.preference.PreferenceGroup, savedState: Bundle) {
        for (i in 0 until group.preferenceCount) {
            val preference = group.getPreference(i)
            when (preference) {
                is androidx.preference.EditTextPreference -> {
                    val savedValue = savedState.getString(preference.key)
                    if (savedValue != null) {
                        preference.text = savedValue
                    }
                }
                is androidx.preference.SwitchPreferenceCompat -> {
                    preference.isChecked = savedState.getBoolean(preference.key, preference.isChecked)
                }
                is androidx.preference.ListPreference -> {
                    val savedValue = savedState.getString(preference.key)
                    if (savedValue != null) {
                        preference.value = savedValue
                    }
                }
                is androidx.preference.PreferenceCategory -> {
                    restorePreferenceState(preference as androidx.preference.PreferenceGroup, savedState)
                }
                is androidx.preference.PreferenceGroup -> {
                    restorePreferenceState(preference, savedState)
                }
            }
        }
    }

    private fun setupPreferenceListeners() {
        findPreference<Preference>("clock_widget_settings")?.setOnPreferenceClickListener {
            val dialog = WidgetPreferenceDialog.newInstance(WidgetType.CLOCK)
            dialog.show(childFragmentManager, "clock_widget_settings")
            true
        }

        findPreference<Preference>("weather_widget_settings")?.setOnPreferenceClickListener {
            val dialog = WidgetPreferenceDialog.newInstance(WidgetType.WEATHER)
            dialog.show(childFragmentManager, "weather_widget_settings")
            true
        }

        findPreference<Preference>("music_widget_settings")?.setOnPreferenceClickListener {
            val dialog = WidgetPreferenceDialog.newInstance(WidgetType.MUSIC)
            dialog.show(childFragmentManager, "music_widget_settings")
            true
        }
    }

    private fun updateWidgetSummaries() {
        findPreference<Preference>("clock_widget_settings")?.apply {
            val enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("show_clock", false)
            summary = if (enabled) {
                getString(R.string.pref_clock_widget_enabled_summary)
            } else {
                getString(R.string.pref_widget_settings_summary)
            }
        }

        findPreference<Preference>("weather_widget_settings")?.apply {
            val enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("show_weather", false)
            summary = if (enabled) {
                getString(R.string.pref_show_weather_summary)
            } else {
                getString(R.string.pref_widget_settings_summary)
            }
        }

        findPreference<Preference>("music_widget_settings")?.apply {
            val enabled = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean("show_music", false)
            summary = if (enabled) {
                getString(R.string.pref_music_widget_enabled_summary)
            } else {
                getString(R.string.pref_widget_settings_summary)
            }
        }
    }

    fun cancelChanges() {
        initialPreferences?.let { savedState ->
            restorePreferenceState(preferenceScreen, savedState)
        }
    }

    fun applyChanges() {
        // No need to do anything here, as preferences are saved automatically
        Log.d(TAG, "Widget settings applied")
    }
}