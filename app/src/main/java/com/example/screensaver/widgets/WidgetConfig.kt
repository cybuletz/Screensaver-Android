package com.example.screensaver.widgets

sealed class WidgetConfig {
    data class ClockConfig(
        val showClock: Boolean = true,
        val showDate: Boolean = true,
        val use24Hour: Boolean = true,
        val dateFormat: String = "MMMM d, yyyy",
        val timeFormat: String = "HH:mm",
        val position: WidgetPosition = WidgetPosition.TOP_START
    ) : WidgetConfig()

    data class WeatherConfig(
        val enabled: Boolean = false,
        val useCelsius: Boolean = true,
        val position: WidgetPosition = WidgetPosition.TOP_END,
        val useDeviceLocation: Boolean = true,
        val manualLocation: String = "",
        val updateInterval: Long = 1800000 // 30 minutes in milliseconds
    ) : WidgetConfig()

    data class MusicConfig(
        val enabled: Boolean = false,
        val position: WidgetPosition = WidgetPosition.BOTTOM_CENTER,
        val showControls: Boolean = true,
        val showProgress: Boolean = true,
        val autoplay: Boolean = false,
        val showArtwork: Boolean = true
    ) : WidgetConfig()
}

enum class WidgetPosition {
    TOP_START,
    TOP_CENTER,
    TOP_END,
    BOTTOM_START,
    BOTTOM_CENTER,
    BOTTOM_END
}

enum class WidgetType {
    CLOCK,
    WEATHER,
    MUSIC
}