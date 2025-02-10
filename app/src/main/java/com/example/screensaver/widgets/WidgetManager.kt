package com.example.screensaver.widgets

import android.content.Context
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import android.view.ViewGroup
import java.text.SimpleDateFormat
import java.util.Locale

@Singleton
class WidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {
    private val widgets = mutableMapOf<WidgetType, ScreenWidget>()
    private val _widgetStates = MutableStateFlow<Map<WidgetType, WidgetData>>(emptyMap())
    val widgetStates: StateFlow<Map<WidgetType, WidgetData>> = _widgetStates.asStateFlow()

    private var lastKnownContainer: ViewGroup? = null

    companion object {
        private const val TAG = "WidgetManager"
    }

    fun registerWidget(type: WidgetType, widget: ScreenWidget) {
        widgets[type] = widget
        updateWidgetState(type, WidgetState.Loading)
        Log.d(TAG, "Registered widget: $type")
    }

    fun unregisterWidget(type: WidgetType) {
        widgets.remove(type)?.cleanup()
        updateWidgetState(type, WidgetState.Hidden)
        Log.d(TAG, "Unregistered widget: $type")
    }

    fun updateWidgetConfig(type: WidgetType, config: WidgetConfig) {
        widgets[type]?.updateConfiguration(config)
        val currentStates = _widgetStates.value.toMutableMap()
        currentStates[type] = currentStates[type]?.copy(config = config)
            ?: WidgetData(type = type, config = config)
        _widgetStates.value = currentStates
        Log.d(TAG, "Updated config for widget: $type")
    }

    private fun updateWidgetState(type: WidgetType, state: WidgetState) {
        val currentStates = _widgetStates.value.toMutableMap()
        currentStates[type] = currentStates[type]?.copy(state = state)
            ?: WidgetData(type = type, state = state)
        _widgetStates.value = currentStates
        Log.d(TAG, "Updated state for widget: $type - $state")
    }

    fun showWidget(type: WidgetType) {
        widgets[type]?.show()
        updateWidgetState(type, WidgetState.Active)
    }

    fun hideWidget(type: WidgetType) {
        widgets[type]?.apply {
            hide()
            cleanup() // Add cleanup to fully remove the widget
        }
        updateWidgetState(type, WidgetState.Hidden)
    }

    fun setupClockWidget(container: ViewGroup) {
        Log.d(TAG, "Setting up clock widget with container type: ${container.javaClass.simpleName}")
        val config = loadClockConfig()
        Log.d(TAG, "Loaded clock config: $config")

        try {
            // Store current weather state before modifying anything
            val weatherState = _widgetStates.value[WidgetType.WEATHER]
            val isWeatherActive = weatherState?.state is WidgetState.Active

            val clockWidget = ClockWidget(container, config)
            Log.d(TAG, "Created ClockWidget instance")

            registerWidget(WidgetType.CLOCK, clockWidget)
            Log.d(TAG, "Clock widget registered")

            clockWidget.init()
            Log.d(TAG, "Clock widget initialized")

            // Show the clock widget if enabled in config
            if (config.showClock) {
                showWidget(WidgetType.CLOCK)
                Log.d(TAG, "Show clock widget called")
            }

            // Restore weather widget if it was active
            if (isWeatherActive) {
                widgets[WidgetType.WEATHER]?.let {
                    it.show()
                    updateWidgetState(WidgetType.WEATHER, WidgetState.Active)
                    Log.d(TAG, "Restored weather widget state")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up clock widget", e)
        }
    }

    private fun loadClockConfig(): WidgetConfig.ClockConfig {
        val showClock = preferences.isShowClock()
        val showDate = preferences.getShowDate()
        val position = parseWidgetPosition(preferences.getString("clock_position", "TOP_START"))

        // Use safe default formats
        val dateFormat = try {
            preferences.getString("date_format", "MMMM d, yyyy").also { format ->
                // Validate format
                SimpleDateFormat(format, Locale.getDefault())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid date format, using default", e)
            "MMMM d, yyyy"
        }

        val timeFormat = try {
            preferences.getString("time_format", "HH:mm").also { format ->
                // Validate format
                SimpleDateFormat(format, Locale.getDefault())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid time format, using default", e)
            "HH:mm"
        }

        Log.d(TAG, "Loading config - showClock: $showClock, showDate: $showDate, " +
                "position: $position, dateFormat: $dateFormat, timeFormat: $timeFormat")

        return WidgetConfig.ClockConfig(
            showClock = showClock,
            showDate = showDate,
            use24Hour = preferences.getString("clock_format", "24h") == "24h",
            dateFormat = dateFormat,
            timeFormat = timeFormat,
            position = position
        )
    }

    fun reinitializeClockWidget(container: ViewGroup? = null) {
        Log.d(TAG, "Reinitializing clock widget")
        val widget = widgets[WidgetType.CLOCK] as? ClockWidget
        if (widget != null) {
            Log.d(TAG, "Existing widget found, updating")
            val config = loadClockConfig()

            // First hide and cleanup the old widget
            hideWidget(WidgetType.CLOCK)

            // Then create a new widget if needed
            if (config.showClock) {
                container?.let {
                    setupClockWidget(it)
                }
            }
        } else if (container != null) {
            val config = loadClockConfig()
            if (config.showClock) {
                setupClockWidget(container)
            }
        }
    }

    private fun parseWidgetPosition(position: String): WidgetPosition {
        return try {
            WidgetPosition.valueOf(position)
        } catch (e: IllegalArgumentException) {
            WidgetPosition.TOP_START
        }
    }

    fun updateClockConfig() {
        Log.d(TAG, "Updating clock config")
        val config = loadClockConfig()
        Log.d(TAG, "New config loaded: $config")
        updateWidgetConfig(WidgetType.CLOCK, config)
        reinitializeClockWidget() // Add this line
    }

    fun updateClockPosition(position: WidgetPosition) {
        val currentConfig = (widgets[WidgetType.CLOCK] as? ClockWidget)?.config as? WidgetConfig.ClockConfig
            ?: return

        val newConfig = currentConfig.copy(position = position)
        updateWidgetConfig(WidgetType.CLOCK, newConfig)
        preferences.setClockPosition(position.name)
        Log.d(TAG, "Clock position updated: $position")
    }

    fun setupWeatherWidget(container: ViewGroup) {
        lastKnownContainer = container  // Store the container
        Log.d(TAG, "Setting up weather widget")
        val config = loadWeatherConfig()
        Log.d(TAG, "Loaded weather config: $config")

        try {
            val weatherWidget = WeatherWidget(container, config)
            Log.d(TAG, "Created WeatherWidget instance")

            registerWidget(WidgetType.WEATHER, weatherWidget)
            Log.d(TAG, "Weather widget registered")

            weatherWidget.init()
            Log.d(TAG, "Weather widget initialized")

            if (config.enabled) {
                showWidget(WidgetType.WEATHER)
            } else {
                hideWidget(WidgetType.WEATHER)
            }
            Log.d(TAG, "Weather widget visibility set to: ${config.enabled}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up weather widget", e)
        }
    }

    private fun loadWeatherConfig(): WidgetConfig.WeatherConfig {
        val showWeather = preferences.getBoolean("show_weather", false)
        val position = parseWidgetPosition(preferences.getString("weather_position", "TOP_END"))
        val useCelsius = preferences.getBoolean("weather_use_celsius", true)
        val updateInterval = preferences.getString("weather_update_interval", "1800")?.toLong()?.times(1000) ?: 1800000
        val useDeviceLocation = preferences.getBoolean("weather_use_device_location", false)

        Log.d(TAG, "Loading weather config - showWeather: $showWeather, " +
                "position: $position, useCelsius: $useCelsius, " +
                "updateInterval: $updateInterval")

        return WidgetConfig.WeatherConfig(
            enabled = showWeather,
            position = position,
            useCelsius = useCelsius,
            updateInterval = updateInterval,
            useDeviceLocation = useDeviceLocation
        )
    }

    fun updateWeatherConfig() {
        Log.d(TAG, "Updating weather config")
        val config = loadWeatherConfig()
        Log.d(TAG, "New config loaded: $config")

        // Get the current widget before updating config
        val currentWidget = widgets[WidgetType.WEATHER] as? WeatherWidget

        if (currentWidget != null) {
            // If we have an existing widget, just update its config
            updateWidgetConfig(WidgetType.WEATHER, config)
            if (config.enabled) {
                currentWidget.show()
            } else {
                currentWidget.hide()
            }
        } else {
            // If no widget exists and we have a container, create a new one
            lastKnownContainer?.let { container ->
                setupWeatherWidget(container)
            }
        }
    }

    fun reinitializeWeatherWidget(container: ViewGroup? = null) {
        Log.d(TAG, "Reinitializing weather widget")
        try {
            // First, clean up existing widget
            widgets[WidgetType.WEATHER]?.cleanup()
            widgets.remove(WidgetType.WEATHER)

            // Get current config
            val config = loadWeatherConfig()
            Log.d(TAG, "Loaded weather config: $config")

            if (config.enabled && container != null) {
                // Create and setup new widget
                val weatherWidget = WeatherWidget(container, config)
                registerWidget(WidgetType.WEATHER, weatherWidget)
                weatherWidget.init()

                // Explicitly show the widget
                showWidget(WidgetType.WEATHER)
                Log.d(TAG, "Weather widget reinitialized and shown")
            } else {
                Log.d(TAG, "Weather widget not enabled or container is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reinitializing weather widget", e)
        }
    }

    fun updateWeatherVisibility(visible: Boolean) {
        Log.d(TAG, "Updating weather visibility: $visible")
        preferences.setShowWeather(visible)

        val currentWidget = widgets[WidgetType.WEATHER] as? WeatherWidget
        if (currentWidget != null) {
            if (visible) {
                currentWidget.show()
            } else {
                currentWidget.hide()
            }
        } else if (visible) {
            // If widget doesn't exist but should be visible, create it
            lastKnownContainer?.let { container ->
                setupWeatherWidget(container)
            }
        }
    }

    fun updateWeatherLocation(location: String) {
        val currentConfig = (widgets[WidgetType.WEATHER] as? WeatherWidget)?.config
            ?: return
        val newConfig = currentConfig.copy(
            manualLocation = location,
            useDeviceLocation = false
        )
        updateWidgetConfig(WidgetType.WEATHER, newConfig)
        preferences.setString("weather_manual_location", location)
        Log.d(TAG, "Weather manual location updated to: $location")
    }

    fun initializeWeatherWidget() {
        val config = loadWeatherConfig()
        updateWidgetConfig(WidgetType.WEATHER, config)
        if (config.enabled) {
            showWidget(WidgetType.WEATHER)
        } else {
            hideWidget(WidgetType.WEATHER)
        }
    }

    fun updateWeatherPosition(position: WidgetPosition) {
        val currentConfig = (widgets[WidgetType.WEATHER] as? WeatherWidget)?.config
            ?: return

        val newConfig = currentConfig.copy(position = position)

        // First update the config
        updateWidgetConfig(WidgetType.WEATHER, newConfig)
        preferences.setString("weather_position", position.name)

        // Instead of letting the widget handle everything internally,
        // we'll manage the transition explicitly
        val currentWidget = widgets[WidgetType.WEATHER] as? WeatherWidget
        if (currentWidget != null) {
            // Store the current container
            val container = lastKnownContainer ?: return

            // Create new widget with new position but preserve state
            val weatherWidget = WeatherWidget(container, newConfig)

            // Initialize but don't show yet
            weatherWidget.init()

            // Clean up old widget
            currentWidget.cleanup()

            // Register new widget
            registerWidget(WidgetType.WEATHER, weatherWidget)

            // Show the widget if it was previously enabled
            if (newConfig.enabled) {
                showWidget(WidgetType.WEATHER)
            }
        }

        Log.d(TAG, "Weather position updated: $position")
    }

    fun cleanup() {
        Log.d(TAG, "Starting cleanup of all widgets")

        // Clean up widgets individually
        widgets.forEach { (type, widget) ->
            try {
                Log.d(TAG, "Cleaning up widget: $type")
                widget.cleanup()
                updateWidgetState(type, WidgetState.Hidden)
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up widget: $type", e)
            }
        }

        // Clear collections
        widgets.clear()
        _widgetStates.value = emptyMap()

        // Clear container reference
        lastKnownContainer = null

        Log.d(TAG, "Completed cleanup of all widgets")
    }
}