package com.example.screensaver.widgets

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Log

@AndroidEntryPoint
class WidgetPreferenceFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var widgetManager: WidgetManager

    companion object {
        private const val TAG = "WidgetPreferenceFragment"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.widget_preferences, rootKey)
        setupPreferenceListeners()
    }

    private fun setupPreferenceListeners() {
        val clockPrefs = listOf(
            "show_clock",
            "show_date",
            "clock_format",
            "clock_position"
        )

        clockPrefs.forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                Log.d(TAG, "Preference changed: $key = $newValue")
                handleClockPreferenceChange(key, newValue)
                true
            }
        }
    }

    private fun handleClockPreferenceChange(key: String, newValue: Any) {
        when (key) {
            "show_clock" -> {
                val show = newValue as Boolean
                if (show) {
                    widgetManager.showWidget(WidgetType.CLOCK)
                } else {
                    widgetManager.hideWidget(WidgetType.CLOCK)
                }
            }
            else -> {
                // For all other changes, just update the config
                widgetManager.updateClockConfig()
            }
        }
    }
}