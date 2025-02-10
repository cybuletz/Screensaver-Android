package com.example.screensaver.widgets

interface ScreenWidget {
    fun init()
    fun update()
    fun show()
    fun hide()
    fun cleanup()
    fun updateConfiguration(config: WidgetConfig)
    fun updatePosition(position: WidgetPosition)
}