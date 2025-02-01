package com.example.screensaver.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Log

@AndroidEntryPoint
class PhotoDisplaySettingsFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.photo_display_preferences, rootKey)

        // Find the photo_interval preference
        findPreference<SeekBarPreference>("photo_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    // Ensure the value is saved as an integer
                    val intValue = (newValue as? Int) ?: (newValue.toString().toInt())
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putInt("photo_interval", intValue)
                        .apply()
                    true
                } catch (e: Exception) {
                    Log.e("PhotoDisplaySettings", "Error saving interval", e)
                    false
                }
            }
        }
    }

    private fun setupPreferenceListeners() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        findPreference<SeekBarPreference>("transition_duration")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    transitionDuration = (newValue as Int).toLong()
                )
                true
            }
        }

        findPreference<SeekBarPreference>("photo_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    photoInterval = (newValue as Int).toLong()
                )
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("show_clock")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    showClock = newValue as Boolean
                )
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("show_date")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    showDate = newValue as Boolean
                )
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("show_location")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    showLocation = newValue as Boolean
                )
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("random_order")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                photoDisplayManager.updateSettings(
                    isRandomOrder = newValue as Boolean
                )
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Update UI with current settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        photoDisplayManager.updateSettings(
            transitionDuration = prefs.getInt("transition_duration", 1000).toLong(),
            photoInterval = prefs.getInt("photo_interval", 10000).toLong(),
            showClock = prefs.getBoolean("show_clock", true),
            showDate = prefs.getBoolean("show_date", true),
            showLocation = prefs.getBoolean("show_location", false),
            isRandomOrder = prefs.getBoolean("random_order", false)
        )
    }
}