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
        widgets[type]?.hide()
        updateWidgetState(type, WidgetState.Hidden)
    }

    fun setupClockWidget(container: ViewGroup) {
        Log.d(TAG, "Setting up clock widget with container type: ${container.javaClass.simpleName}")
        val config = loadClockConfig()
        Log.d(TAG, "Loaded clock config: $config")

        try {
            val clockWidget = ClockWidget(container, config)
            Log.d(TAG, "Created ClockWidget instance")

            registerWidget(WidgetType.CLOCK, clockWidget)
            Log.d(TAG, "Clock widget registered")

            clockWidget.init()
            Log.d(TAG, "Clock widget initialized")

            // Always show the widget after initialization, visibility will be handled by the widget itself
            showWidget(WidgetType.CLOCK)
            Log.d(TAG, "Show widget called")
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
            widget.updateConfiguration(config)
            if (config.showClock) {
                Log.d(TAG, "Showing widget after config update")
                showWidget(WidgetType.CLOCK)
            } else {
                Log.d(TAG, "Hiding widget after config update")
                hideWidget(WidgetType.CLOCK)
            }
        } else {
            Log.e(TAG, "No clock widget found to reinitialize")
            container?.let {
                setupClockWidget(it)
            } ?: Log.e(TAG, "No container provided for widget initialization")
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

            // Show widget based on preferences
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

        Log.d(TAG, "Loading weather config - showWeather: $showWeather, " +
                "position: $position, useCelsius: $useCelsius, " +
                "updateInterval: $updateInterval")

        return WidgetConfig.WeatherConfig(
            enabled = showWeather,
            position = position,
            useCelsius = useCelsius,
            updateInterval = updateInterval
        )
    }

    fun updateWeatherConfig() {
        Log.d(TAG, "Updating weather config")
        val config = loadWeatherConfig()
        Log.d(TAG, "New config loaded: $config")
        updateWidgetConfig(WidgetType.WEATHER, config)
        reinitializeWeatherWidget()
    }

    fun reinitializeWeatherWidget(container: ViewGroup? = null) {
        Log.d(TAG, "Reinitializing weather widget")
        val widget = widgets[WidgetType.WEATHER] as? WeatherWidget
        if (widget != null) {
            Log.d(TAG, "Existing widget found, updating")
            val config = loadWeatherConfig()
            widget.updateConfiguration(config)
            if (config.enabled) {
                Log.d(TAG, "Showing widget after config update")
                showWidget(WidgetType.WEATHER)
            } else {
                Log.d(TAG, "Hiding widget after config update")
                hideWidget(WidgetType.WEATHER)
            }
        } else {
            Log.e(TAG, "No weather widget found to reinitialize")
            container?.let {
                setupWeatherWidget(it)
            } ?: Log.e(TAG, "No container provided for widget initialization")
        }
    }

    fun updateWeatherPosition(position: WidgetPosition) {
        val currentConfig = (widgets[WidgetType.WEATHER] as? WeatherWidget)?.config
            ?: return

        val newConfig = currentConfig.copy(position = position)
        updateWidgetConfig(WidgetType.WEATHER, newConfig)
        preferences.setString("weather_position", position.name)
        Log.d(TAG, "Weather position updated: $position")
    }

    fun cleanup() {
        widgets.values.forEach { it.cleanup() }
        widgets.clear()
        _widgetStates.value = emptyMap()
        Log.d(TAG, "Cleaned up all widgets")
    }
}