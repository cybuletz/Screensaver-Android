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
        Log.d(TAG, "Setting up clock widget")
        val config = loadClockConfig()
        Log.d(TAG, "Loaded clock config: $config")

        val clockWidget = ClockWidget(container, config)
        registerWidget(WidgetType.CLOCK, clockWidget)
        Log.d(TAG, "Clock widget registered")

        clockWidget.init()
        Log.d(TAG, "Clock widget initialized")

        if (config.showClock) {
            Log.d(TAG, "Showing clock widget")
            showWidget(WidgetType.CLOCK)
        } else {
            Log.d(TAG, "Hiding clock widget")
            hideWidget(WidgetType.CLOCK)
        }
    }

    private fun loadClockConfig(): WidgetConfig.ClockConfig {
        return WidgetConfig.ClockConfig(
            showClock = preferences.isShowClock(),
            showDate = preferences.getShowDate(),
            use24Hour = preferences.getString("clock_format", "24h") == "24h",
            dateFormat = preferences.getString("date_format", "MMMM d, yyyy"),
            timeFormat = preferences.getString("time_format", "HH:mm"),
            position = parseWidgetPosition(preferences.getString("clock_position", "TOP_START"))
        )
    }

    private fun parseWidgetPosition(position: String): WidgetPosition {
        return try {
            WidgetPosition.valueOf(position)
        } catch (e: IllegalArgumentException) {
            WidgetPosition.TOP_START
        }
    }

    fun updateClockConfig() {
        val config = loadClockConfig()
        updateWidgetConfig(WidgetType.CLOCK, config)
        Log.d(TAG, "Clock config updated: $config")
    }

    fun updateClockPosition(position: WidgetPosition) {
        val currentConfig = (widgets[WidgetType.CLOCK] as? ClockWidget)?.config as? WidgetConfig.ClockConfig
            ?: return

        val newConfig = currentConfig.copy(position = position)
        updateWidgetConfig(WidgetType.CLOCK, newConfig)
        preferences.setClockPosition(position.name)
        Log.d(TAG, "Clock position updated: $position")
    }

    fun cleanup() {
        widgets.values.forEach { it.cleanup() }
        widgets.clear()
        _widgetStates.value = emptyMap()
        Log.d(TAG, "Cleaned up all widgets")
    }
}