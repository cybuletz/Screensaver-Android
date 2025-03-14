package com.example.screensaver.settings

import android.os.Bundle
import androidx.preference.*
import com.example.screensaver.R
import com.example.screensaver.ui.PhotoDisplayManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CommonSettingsPreferenceFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    private var initialPreferences: Bundle? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.common_settings_preferences, rootKey)
        initialPreferences = Bundle().apply {
            savePreferenceState(preferenceScreen, this)
        }

        setupPreferences()
    }

    private fun setupPreferences() {
        // Random order preference
        findPreference<SwitchPreferenceCompat>("random_order")?.setOnPreferenceChangeListener { _, newValue ->
            val isRandomOrder = newValue as Boolean
            photoDisplayManager.updateSettings(isRandomOrder = isRandomOrder)
            true
        }

        // Transition duration
        findPreference<SeekBarPreference>("transition_duration")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val duration = (newValue as Int)
                photoDisplayManager.updateSettings(transitionDuration = duration * 1000L)
                summary = "$duration seconds for transition animation"
                true
            }

            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt("transition_duration", 2)
            summary = "$currentValue seconds for transition animation"
        }

        // Photo interval
        findPreference<SeekBarPreference>("photo_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val interval = (newValue as Int)
                summary = "Display each photo for $interval seconds"
                photoDisplayManager.apply {
                    stopPhotoDisplay()
                    startPhotoDisplay()
                }
                true
            }

            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt(PhotoDisplayManager.PREF_KEY_INTERVAL, PhotoDisplayManager.DEFAULT_INTERVAL_SECONDS)
            summary = "Display each photo for $currentValue seconds"
        }

        // Photo scale preference - store in SharedPreferences only
        findPreference<ListPreference>("photo_scale")?.setOnPreferenceChangeListener { _, _ ->
            photoDisplayManager.apply {
                stopPhotoDisplay()
                startPhotoDisplay()
            }
            true
        }

        // Transition effect - store in SharedPreferences only
        findPreference<ListPreference>("transition_effect")?.setOnPreferenceChangeListener { _, _ ->
            photoDisplayManager.apply {
                stopPhotoDisplay()
                startPhotoDisplay()
            }
            true
        }
    }

    private fun savePreferenceState(screen: PreferenceScreen, outState: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            preference.sharedPreferences?.let { prefs ->
                when (preference.key) {
                    "random_order" -> outState.putBoolean(preference.key, prefs.getBoolean(preference.key, true))
                    "photo_scale" -> outState.putString(preference.key, prefs.getString(preference.key, "fill"))
                    "transition_effect" -> outState.putString(preference.key, prefs.getString(preference.key, "fade"))
                    "transition_duration" -> outState.putInt(preference.key, prefs.getInt(preference.key, 2))
                    "photo_interval" -> outState.putInt(preference.key, prefs.getInt(preference.key, 5))
                }
            }
        }
    }

    fun cancelChanges() {
        val prefs = preferenceManager.sharedPreferences
        initialPreferences?.let { initial ->
            initial.keySet().forEach { key ->
                prefs?.edit()?.apply {
                    when (key) {
                        "random_order" -> putBoolean(key, initial.getBoolean(key))
                        "photo_scale" -> putString(key, initial.getString(key))
                        "transition_effect" -> putString(key, initial.getString(key))
                        "transition_duration" -> putInt(key, initial.getInt(key))
                        "photo_interval" -> putInt(key, initial.getInt(key))
                    }
                    apply()
                }
            }
        }
        // Reset PhotoDisplayManager with original settings
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings()
            startPhotoDisplay()
        }
    }

    fun applyChanges() {
        // Force restart photo display to ensure all changes are applied
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings()
            startPhotoDisplay()
        }
    }
}