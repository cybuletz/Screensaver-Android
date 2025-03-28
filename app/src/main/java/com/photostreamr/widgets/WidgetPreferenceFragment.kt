package com.photostreamr.widgets

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.photostreamr.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Log
import androidx.preference.PreferenceManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder


@AndroidEntryPoint
class WidgetPreferenceFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {

    @Inject
    lateinit var widgetManager: WidgetManager

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions.entries.any { it.value }

        handleLocationPermissionResult(locationGranted)
    }

    companion object {
        private const val TAG = "WidgetPreferenceFragment"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val widgetType = arguments?.getString("widget_type")?.let {
            WidgetType.valueOf(it)
        } ?: return

        when (widgetType) {
            WidgetType.CLOCK -> setPreferencesFromResource(R.xml.widget_clock_preferences, rootKey)
            WidgetType.WEATHER -> setPreferencesFromResource(R.xml.widget_weather_preferences, rootKey)
            WidgetType.MUSIC -> setPreferencesFromResource(R.xml.widget_music_preferences, rootKey)
        }

        setupPreferenceListeners()
    }

    private fun checkLocationPermissions() {
        // Delegate to existing checkLocationPermission() method
        checkLocationPermission()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.error_dialog_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun createClockConfig(): WidgetConfig.ClockConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return WidgetConfig.ClockConfig(
            showClock = prefs.getBoolean("show_clock", false),
            showDate = prefs.getBoolean("show_date", false),
            use24Hour = prefs.getString("clock_format", "24h") == "24h",
            dateFormat = prefs.getString("date_format", "MMMM d, yyyy") ?: "MMMM d, yyyy",
            timeFormat = prefs.getString("time_format", "HH:mm") ?: "HH:mm",
            position = WidgetPosition.valueOf(prefs.getString("clock_position", "TOP_START") ?: "TOP_START")
        )
    }

    private fun createMusicConfig(): WidgetConfig.MusicConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return WidgetConfig.MusicConfig(
            enabled = prefs.getBoolean("show_music", false),
            position = WidgetPosition.valueOf(prefs.getString("music_position", "BOTTOM_CENTER") ?: "BOTTOM_CENTER"),
            showControls = prefs.getBoolean("show_music_controls", true),
            showProgress = prefs.getBoolean("show_music_progress", true),
            autoplay = prefs.getBoolean("spotify_autoplay", false)
        )
    }

    private fun handleLocationPermissionResult(granted: Boolean) {
        if (granted) {
            // Update weather config with location enabled
            val config = createWeatherConfig().copy(useDeviceLocation = true)
            widgetManager.updateWidgetConfig(WidgetType.WEATHER, config)
        } else {
            // If permission denied, uncheck the preference
            findPreference<SwitchPreferenceCompat>("weather_use_device_location")?.isChecked = false

            // Show explanation dialog
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.location_permission_denied_title)
                .setMessage(R.string.location_permission_denied_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun checkLocationPermission() {
        when {
            hasLocationPermission() -> {
                // Permission already granted, update config
                val config = createWeatherConfig().copy(useDeviceLocation = true)
                widgetManager.updateWidgetConfig(WidgetType.WEATHER, config)
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) -> {
                showLocationPermissionRationale()
            }
            else -> {
                requestLocationPermission()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showLocationPermissionRationale() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.location_permission_rationale_title)
            .setMessage(R.string.location_permission_rationale_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                requestLocationPermission()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                findPreference<SwitchPreferenceCompat>("weather_use_device_location")?.isChecked = false
            }
            .show()
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        return when (preference.key) {
            "weather_position" -> {
                try {
                    val position = WidgetPosition.valueOf(newValue.toString())
                    widgetManager.updateWeatherPosition(position)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating weather position", e)
                    false
                }
            }
            "clock_position" -> {
                try {
                    val position = WidgetPosition.valueOf(newValue.toString())
                    widgetManager.updateClockPosition(position)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating clock position", e)
                    false
                }
            }
            "show_weather" -> {
                val enabled = newValue as Boolean
                widgetManager.updateWeatherVisibility(enabled)
                true
            }
            "show_clock" -> {
                val enabled = newValue as Boolean
                if (enabled) {
                    widgetManager.showWidget(WidgetType.CLOCK)
                } else {
                    widgetManager.hideWidget(WidgetType.CLOCK)
                }
                true
            }
            "weather_use_device_location" -> {
                val useLocation = newValue as Boolean
                if (useLocation) {
                    checkLocationPermissions()
                }
                true
            }
            "weather_manual_location" -> {
                val location = newValue as String
                if (location.isBlank()) {
                    showError("Location cannot be empty")
                    false
                } else {
                    widgetManager.updateWeatherLocation(location)
                    true
                }
            }
            "show_music" -> {
                val enabled = newValue as Boolean
                widgetManager.updateMusicVisibility(enabled)  // Use this instead of show/hideWidget
                true
            }
            "show_music_controls", "show_music_progress" -> {
                widgetManager.updateMusicWidgetSetting(preference.key, newValue as Boolean)  // Use this instead of direct config update
                true
            }
            "music_position" -> {
                try {
                    val position = WidgetPosition.valueOf(newValue.toString())
                    widgetManager.updateMusicPosition(position)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating music position", e)
                    false
                }
            }
            else -> true
        }
    }

    private fun setupPreferenceListeners() {
        // Register this fragment as the preference change listener for all preferences
        val allPrefs = listOf(
            "show_clock",
            "show_date",
            "clock_format",
            "clock_position",
            "show_weather",
            "weather_position",
            "weather_use_celsius",
            "weather_update_interval",
            "weather_use_device_location",
            "weather_manual_location",
            "show_music",
            "show_music_controls",
            "show_music_progress",
            "music_position"
        )

        allPrefs.forEach { key ->
            findPreference<Preference>(key)?.onPreferenceChangeListener = this
        }

        // Update initial states
        updateInitialStates()
    }

    private fun updateInitialStates() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        // Update widget visibility based on current preferences
        if (prefs.getBoolean("show_clock", false)) {
            widgetManager.showWidget(WidgetType.CLOCK)
        } else {
            widgetManager.hideWidget(WidgetType.CLOCK)
        }

        if (prefs.getBoolean("show_weather", false)) {
            widgetManager.showWidget(WidgetType.WEATHER)
        } else {
            widgetManager.hideWidget(WidgetType.WEATHER)
        }

        // Use updateMusicVisibility for music widget
        widgetManager.updateMusicVisibility(prefs.getBoolean("show_music", false))

        // Update configs with current settings
        widgetManager.updateWidgetConfig(WidgetType.CLOCK, createClockConfig())
        widgetManager.updateWidgetConfig(WidgetType.WEATHER, createWeatherConfig())

        // Update initial music settings
        prefs.getBoolean("show_music_controls", true).also { showControls ->
            widgetManager.updateMusicWidgetSetting("show_music_controls", showControls)
        }
        prefs.getBoolean("show_music_progress", true).also { showProgress ->
            widgetManager.updateMusicWidgetSetting("show_music_progress", showProgress)
        }
    }

    fun createWeatherConfig(): WidgetConfig.WeatherConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return WidgetConfig.WeatherConfig(
            enabled = prefs.getBoolean("show_weather", false),
            useCelsius = prefs.getBoolean("weather_use_celsius", true),
            position = WidgetPosition.valueOf(prefs.getString("weather_position", "TOP_END") ?: "TOP_END"),
            updateInterval = prefs.getString("weather_update_interval", "1800")?.toLong()?.times(1000) ?: 1800000,
            useDeviceLocation = prefs.getBoolean("weather_use_device_location", true),
            manualLocation = prefs.getString("weather_manual_location", "") ?: ""
        )
    }
}