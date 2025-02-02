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
    WEATHER  // Future extension
}