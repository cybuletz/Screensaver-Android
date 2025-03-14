package com.example.screensaver.utils

import android.content.pm.ActivityInfo

enum class ScreenOrientation(val androidValue: Int) {
    SYSTEM(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    AUTO_ROTATE(ActivityInfo.SCREEN_ORIENTATION_SENSOR)
}