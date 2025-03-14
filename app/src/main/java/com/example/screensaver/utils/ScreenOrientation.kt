package com.example.screensaver.utils

enum class ScreenOrientation(val value: Int) {
    SYSTEM(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    AUTO_ROTATE(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR)
}