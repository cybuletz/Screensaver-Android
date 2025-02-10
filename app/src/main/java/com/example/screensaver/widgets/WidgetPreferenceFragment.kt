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
import androidx.preference.PreferenceManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder


@AndroidEntryPoint
class WidgetPreferenceFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var widgetManager: WidgetManager

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
        }

        setupPreferenceListeners()
    }


    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions.entries.any { it.value }

        handleLocationPermissionResult(locationGranted)
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

    private fun setupPreferenceListeners() {
        // Existing clock preferences
        val clockPrefs = listOf(
            "show_clock",
            "show_date",
            "clock_format",
            "clock_position"
        )

        // Add weather preferences
        val weatherPrefs = listOf(
            "show_weather",
            "weather_position",
            "weather_use_celsius",
            "weather_update_interval",
            "weather_use_device_location",
            "weather_manual_location"
        )

        // Setup listeners for both clock and weather preferences
        (clockPrefs + weatherPrefs).forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, newValue ->
                when {
                    key == "show_clock" -> handleClockVisibilityChange(newValue as Boolean)
                    key == "show_weather" -> handleWeatherVisibilityChange(newValue as Boolean)
                    key.startsWith("clock_") -> handleClockPreferenceChange(key, newValue)
                    key.startsWith("weather_") -> handleWeatherPreferenceChange(key, newValue)
                }
                true
            }
        }

        // Setup location permission listener
        findPreference<SwitchPreferenceCompat>("weather_use_device_location")?.setOnPreferenceChangeListener { _, newValue ->
            val useLocation = newValue as Boolean
            if (useLocation) {
                checkLocationPermission()
            } else {
                val config = createWeatherConfig().copy(useDeviceLocation = false)
                widgetManager.updateWidgetConfig(WidgetType.WEATHER, config)
            }
            true
        }
    }

    private fun handleClockVisibilityChange(show: Boolean) {
        if (show) {
            widgetManager.showWidget(WidgetType.CLOCK)
        } else {
            widgetManager.hideWidget(WidgetType.CLOCK)
        }
    }

    private fun handleWeatherVisibilityChange(show: Boolean) {
        Log.d(TAG, "Weather visibility changed to: $show")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        if (show) {
            // First save the preference
            sharedPreferences.edit().apply {
                putBoolean("show_weather", true)
                apply()
            }
            // Then create and apply the config
            val config = createWeatherConfig()
            widgetManager.updateWidgetConfig(WidgetType.WEATHER, config)
            // Finally show the widget
            widgetManager.showWidget(WidgetType.WEATHER)
        } else {
            sharedPreferences.edit().apply {
                putBoolean("show_weather", false)
                apply()
            }
            widgetManager.hideWidget(WidgetType.WEATHER)
        }
    }

    private fun handleWeatherPreferenceChange(key: String, newValue: Any) {
        when (key) {
            "weather_position", "weather_use_celsius", "weather_update_interval",
            "weather_use_device_location", "weather_manual_location" -> {
                // Update the config
                val config = createWeatherConfig()
                widgetManager.updateWidgetConfig(WidgetType.WEATHER, config)
            }
        }
    }

    private fun handleClockPreferenceChange(key: String, newValue: Any) {
        // For all clock changes, just update the config
        widgetManager.updateClockConfig()
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