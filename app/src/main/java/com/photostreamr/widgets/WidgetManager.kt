package com.photostreamr.widgets

import android.content.Context
import com.photostreamr.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import com.photostreamr.music.RadioManager
import com.photostreamr.music.RadioPreferences
import com.photostreamr.music.SpotifyManager
import com.photostreamr.music.SpotifyPreferences
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.preference.PreferenceManager
import com.photostreamr.R


@Singleton
class WidgetManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
) {

    @Inject
    lateinit var spotifyManager: SpotifyManager

    @Inject
    lateinit var spotifyPreferences: SpotifyPreferences

    @Inject
    lateinit var radioManager: RadioManager

    @Inject
    lateinit var radioPreferences: RadioPreferences

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
        widgets[type]?.apply {
            show()
            updateWidgetState(type, WidgetState.Active)
        }
    }

    fun hideWidget(type: WidgetType) {
        widgets[type]?.apply {
            hide()
            // Don't call cleanup here; it will be called separately when needed
        }
        updateWidgetState(type, WidgetState.Hidden)
    }

    fun cleanupAndRemoveWidget(type: WidgetType) {
        Log.d(TAG, "Cleaning up and removing widget: $type")
        try {
            // First hide the widget
            hideWidget(type)

            // Then perform extra cleanup
            widgets[type]?.apply {
                cleanup()
            }

            // Finally remove from widgets map
            widgets.remove(type)

            // Update state to hidden
            updateWidgetState(type, WidgetState.Hidden)

            Log.d(TAG, "Widget successfully cleaned up and removed: $type")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up and removing widget: $type", e)
        }
    }

    fun setupClockWidget(container: ViewGroup) {
        Log.d(TAG, "Setting up clock widget with container type: ${container.javaClass.simpleName}")
        val config = loadClockConfig()
        Log.d(TAG, "Loaded clock config: $config")

        try {
            // Only proceed if the clock is enabled in config
            if (!config.showClock) {
                Log.d(TAG, "Clock widget is disabled in config, not setting up")
                // Make sure any existing widget is removed
                widgets[WidgetType.CLOCK]?.apply {
                    cleanup()
                }
                widgets.remove(WidgetType.CLOCK)
                return
            }

            // First, clean up any existing clock widget to avoid duplicates
            val existingWidget = widgets[WidgetType.CLOCK] as? ClockWidget
            if (existingWidget != null) {
                Log.d(TAG, "Cleaning up existing clock widget")
                existingWidget.cleanup()
                widgets.remove(WidgetType.CLOCK)
            }

            // Create and setup the clock widget
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

        // Save states of other widgets
        val weatherWidget = widgets[WidgetType.WEATHER] as? WeatherWidget
        val weatherState = weatherWidget?.currentWeatherState
        val weatherConfig = weatherWidget?.config

        // Save music widget state
        val musicWidget = widgets[WidgetType.MUSIC] as? MusicControlWidget
        val musicConfig = musicWidget?.config

        if (widget != null) {
            Log.d(TAG, "Existing widget found, updating")
            val config = loadClockConfig()

            // Only cleanup the clock widget
            widget.cleanup()
            widgets.remove(WidgetType.CLOCK)

            // Then create a new clock widget if needed
            if (config.showClock && container != null) {
                setupClockWidget(container)
            }
        } else if (container != null) {
            val config = loadClockConfig()
            if (config.showClock) {
                setupClockWidget(container)
            }
        }

        // Restore weather widget if it was active
        if (weatherState != null && weatherConfig != null && container != null) {
            val widget = widgets[WidgetType.WEATHER] as? WeatherWidget
            if (widget != null) {
                widget.updateState(weatherState)
            } else if (weatherConfig.enabled) {
                setupWeatherWidget(container)
                (widgets[WidgetType.WEATHER] as? WeatherWidget)?.updateState(weatherState)
            }
        }

        // Restore music widget if it was active
        if (musicConfig?.enabled == true && container != null) {
            setupMusicWidget(container)
        }
    }

    private fun parseWidgetPosition(position: String): WidgetPosition {
        return try {
            WidgetPosition.valueOf(position)
        } catch (e: IllegalArgumentException) {
            WidgetPosition.TOP_START
        }
    }

    fun updateClockPosition(position: WidgetPosition) {
        val widget = widgets[WidgetType.CLOCK] as? ClockWidget ?: return
        val currentConfig = widget.config as? WidgetConfig.ClockConfig ?: return

        // Update config with new position
        val newConfig = currentConfig.copy(position = position)

        // Update widget position without reinitialization
        widget.updatePosition(position)

        // Update config and save preference
        updateWidgetConfig(WidgetType.CLOCK, newConfig)
        preferences.setClockPosition(position.name)

        Log.d(TAG, "Clock position updated: $position")
    }

    fun setupWeatherWidget(container: ViewGroup) {
        Log.d(TAG, "Setting up weather widget")
        val config = loadWeatherConfig()
        Log.d(TAG, "Loaded weather config: $config")

        val weatherWidget = WeatherWidget(container, config)
        registerWidget(WidgetType.WEATHER, weatherWidget)
        weatherWidget.init()

        // Show the widget if enabled
        if (config.enabled) {
            showWidget(WidgetType.WEATHER)
        }
    }

    fun loadWeatherConfig(): WidgetConfig.WeatherConfig {
        val showWeather = preferences.getBoolean("show_weather", false)
        val position = parseWidgetPosition(preferences.getString("weather_position", "TOP_END"))
        val useCelsius = preferences.getBoolean("weather_use_celsius", true)
        val updateInterval = preferences.getString("weather_update_interval", "1800")?.toLong()?.times(1000) ?: 1800000
        val useDeviceLocation = preferences.getBoolean("weather_use_device_location", false)

        Log.d(TAG, "Loading weather config - showWeather: $showWeather, " +
                "position: $position, useCelsius: $useCelsius, " +
                "updateInterval: $updateInterval")

        return WidgetConfig.WeatherConfig(
            enabled = showWeather,  // Use the actual preference value
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
            // Get current widget and its state before cleanup
            val currentWidget = widgets[WidgetType.WEATHER] as? WeatherWidget
            val weatherState = currentWidget?.currentWeatherState
            val weatherCode = currentWidget?.currentWeatherCode ?: -1
            val currentConfig = loadWeatherConfig()

            // Only clean up if we need to recreate
            if (currentWidget != null && container != null) {
                currentWidget.cleanup()
                widgets.remove(WidgetType.WEATHER)
            }

            if (currentConfig.enabled && container != null) {
                // Create and setup new widget
                val weatherWidget = WeatherWidget(container, currentConfig).apply {
                    registerWidget(WidgetType.WEATHER, this)
                    init()

                    // Restore previous state if available
                    if (weatherState != null) {
                        restoreState(weatherState.temperature, weatherCode)
                    }

                    // Show the widget
                    if (currentConfig.enabled) {
                        show()
                    }
                }
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

    fun updateWeatherPosition(position: WidgetPosition) {
        Log.d(TAG, "Updating weather widget position to: $position")

        // Get current widget and config
        val widget = widgets[WidgetType.WEATHER] as? WeatherWidget ?: return
        val currentConfig = widget.config as? WidgetConfig.WeatherConfig ?: return

        // Important: Do NOT reinitialize the widget, just update its position
        val newConfig = currentConfig.copy(position = position)
        widget.updateConfiguration(newConfig)

        // Update preferences
        preferences.setString("weather_position", position.name)

        Log.d(TAG, "Weather position updated: $position")
    }

    private fun cleanupWidget(type: WidgetType) {
        try {
            Log.d(TAG, "Cleaning up widget: $type")
            widgets[type]?.apply {
                hide()  // First hide it
                cleanup()  // Then clean it up
            }
            widgets.remove(type)  // Remove from map
            // Update state to hidden
            updateWidgetState(type, WidgetState.Hidden)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up widget: $type", e)
        }
    }

    private fun loadMusicConfig(): WidgetConfig.MusicConfig {
        val enabled = preferences.getBoolean("show_music", false)
        val position = parseWidgetPosition(preferences.getString("music_position", "BOTTOM_CENTER"))
        val showControls = preferences.getBoolean("show_music_controls", true)
        val showProgress = preferences.getBoolean("show_music_progress", true)
        val showArtwork = preferences.getBoolean("show_music_artwork", true)
        val autoplay = spotifyPreferences.isAutoplayEnabled()

        Log.d(TAG, """
        Loading music config:
        - enabled: $enabled
        - position: $position
        - showControls: $showControls
        - showProgress: $showProgress
        - showArtwork: $showArtwork
        - autoplay: $autoplay
    """.trimIndent())

        return WidgetConfig.MusicConfig(
            enabled = enabled,
            position = position,
            showControls = showControls,
            showProgress = showProgress,
            showArtwork = showArtwork, // Add this line
            autoplay = autoplay
        )
    }

    fun updateMusicWidgetSetting(key: String, value: Boolean) {
        Log.d(TAG, "Updating music widget config - key: $key, value: $value")

        when (key) {
            "show_music" -> {
                spotifyPreferences.setEnabled(value)
            }
            "show_music_controls",
            "show_music_progress",
            "show_music_artwork" -> { // Add this case
                preferences.edit { putBoolean(key, value) }
            }
        }

        val widget = widgets[WidgetType.MUSIC] as? MusicControlWidget
        if (widget != null) {
            val newConfig = loadMusicConfig()
            Log.d(TAG, "Applying new config to widget: $newConfig")
            widget.updateConfiguration(newConfig)
        } else {
            Log.d(TAG, "No music widget found to update")
            if (lastKnownContainer != null && value) {
                setupMusicWidget(lastKnownContainer!!)
            }
        }
    }

    fun updateMusicWidgetBasedOnSource() {
        val currentSource = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("music_source", "spotify") ?: "spotify"

        val isSourceEnabled = when (currentSource) {
            "spotify" -> spotifyPreferences.isEnabled()
            "radio" -> radioPreferences.isEnabled()
            else -> false
        }

        // Update preferences to show/hide music widget based on source state
        preferences.edit { putBoolean("show_music", isSourceEnabled) }

        // Update widget config and visibility
        val newConfig = loadMusicConfig().copy(enabled = isSourceEnabled)

        val currentWidget = widgets[WidgetType.MUSIC] as? MusicControlWidget
        if (currentWidget != null) {
            updateWidgetConfig(WidgetType.MUSIC, newConfig)
            if (isSourceEnabled) {
                currentWidget.show()
            } else {
                currentWidget.hide()
                // Clean up when hiding
                if (!isSourceEnabled) {
                    currentWidget.cleanup()
                    widgets.remove(WidgetType.MUSIC)
                }
            }
        } else if (isSourceEnabled && lastKnownContainer != null) {
            // Only create new widget if a source is enabled
            setupMusicWidget(lastKnownContainer!!)
        }

        Log.d(TAG, """
            Music widget update:
            - Current source: $currentSource
            - Source enabled: $isSourceEnabled
            - Widget exists: ${currentWidget != null}
            - Config enabled: ${newConfig.enabled}
        """.trimIndent())
    }

    fun updateMusicVisibility(visible: Boolean) {
        Log.d(TAG, "Updating music visibility: $visible")
        preferences.edit { putBoolean("show_music", visible) }  // Store preference correctly
        spotifyPreferences.setEnabled(visible)

        val currentWidget = widgets[WidgetType.MUSIC] as? MusicControlWidget
        if (currentWidget != null) {
            if (visible) {
                currentWidget.show()
            } else {
                currentWidget.hide()
                // Important: Clean up after hiding
                currentWidget.cleanup()
                widgets.remove(WidgetType.MUSIC)
            }
        } else if (visible) {
            Log.d(TAG, "No music widget exists, creating new one")
            lastKnownContainer?.let { container ->
                // Get the widgets_layer
                val widgetsLayer = container.findViewById<ConstraintLayout>(R.id.widgets_layer)
                if (widgetsLayer != null) {
                    Log.d(TAG, "Setting up new music widget")
                    setupMusicWidget(widgetsLayer)
                } else {
                    Log.e(TAG, "widgets_layer not found in container")
                }
            } ?: Log.e(TAG, "No container available to create music widget")
        }
    }

    fun setupMusicWidget(container: ViewGroup) {
        Log.d(TAG, "Setting up music widget")
        try {
            // Get the widgets_layer
            val widgetsLayer = container.findViewById<ConstraintLayout>(R.id.widgets_layer)
                ?: throw IllegalStateException("widgets_layer not found")

            // Load music config
            val config = loadMusicConfig().also {
                Log.d(TAG, """
            Loading music config:
            - enabled: ${it.enabled}
            - position: ${it.position}
            - showControls: ${it.showControls}
            - showProgress: ${it.showProgress}
            - showArtwork: ${it.showArtwork}
            - autoplay: ${it.autoplay}
        """.trimIndent())
            }

            // Important: Check if widget should actually be shown
            if (!config.enabled) {
                Log.d(TAG, "Music widget is disabled, not creating it")
                return
            }

            // Save container reference
            lastKnownContainer = widgetsLayer

            // Clean up existing widget if present
            (widgets[WidgetType.MUSIC] as? MusicControlWidget)?.cleanup()
            widgets.remove(WidgetType.MUSIC)

            // Create new widget with widgets_layer as container
            val musicWidget = MusicControlWidget(
                container = widgetsLayer,
                config = config,
                spotifyManager = spotifyManager,
                spotifyPreferences = spotifyPreferences,
                radioManager = radioManager,
                radioPreferences = radioPreferences
            ).also {
                Log.d(TAG, "Created MusicControlWidget instance")
            }

            // Initialize and register
            musicWidget.init()
            Log.d(TAG, "MusicWidget initialized")

            registerWidget(WidgetType.MUSIC, musicWidget)
            Log.d(TAG, "MusicWidget registered")

            // Force visibility update based on config
            if (config.enabled) {
                Log.d(TAG, "Music widget enabled, showing widget")
                musicWidget.show()
                updateWidgetState(WidgetType.MUSIC, WidgetState.Active)
            } else {
                Log.d(TAG, "Music widget disabled, hiding widget")
                musicWidget.hide()
                updateWidgetState(WidgetType.MUSIC, WidgetState.Hidden)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up music widget", e)
            updateWidgetState(WidgetType.MUSIC, WidgetState.Error(e.message ?: "Unknown error"))
        }
    }

    fun updateMusicConfig() {
        Log.d(TAG, "Updating music widget config")
        val config = loadMusicConfig()
        Log.d(TAG, "New config loaded: $config")

        val currentWidget = widgets[WidgetType.MUSIC] as? MusicControlWidget
        if (currentWidget != null) {
            updateWidgetConfig(WidgetType.MUSIC, config)
            if (config.enabled) {
                currentWidget.show()
            } else {
                currentWidget.hide()
            }
        } else if (config.enabled) {
            Log.d(TAG, "No music widget exists but config is enabled, creating new one")
            lastKnownContainer?.let { container ->
                // Get the widgets_layer
                val widgetsLayer = container.findViewById<ConstraintLayout>(R.id.widgets_layer)
                if (widgetsLayer != null) {
                    Log.d(TAG, "Setting up new music widget")
                    setupMusicWidget(widgetsLayer)
                } else {
                    Log.e(TAG, "widgets_layer not found in container")
                }
            } ?: Log.e(TAG, "No container available to create music widget")
        }
    }

    fun updateMusicPosition(position: WidgetPosition) {
        Log.d(TAG, "Updating music widget position to: $position")

        val widget = widgets[WidgetType.MUSIC] as? MusicControlWidget ?: return
        val currentConfig = widget.config as? WidgetConfig.MusicConfig ?: return

        // Update widget position without reinitialization
        val newConfig = currentConfig.copy(position = position)
        widget.updateConfiguration(newConfig)

        // Update preferences
        preferences.setString("music_position", position.name)

        Log.d(TAG, "Music position updated: $position")
    }

    fun setContainer(container: ConstraintLayout) {
        Log.d(TAG, "Setting new container reference")
        lastKnownContainer = container
    }

    fun cleanup() {
        Log.d(TAG, "Starting cleanup of all widgets")

        // Clean up each widget properly
        WidgetType.values().forEach { type ->
            cleanupWidget(type)
        }

        // Clear all collections
        widgets.clear()
        _widgetStates.value = emptyMap()

        // Clear container reference
        lastKnownContainer = null

        Log.d(TAG, "Completed cleanup of all widgets")
    }

}

