package com.photostreamr.utils

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrightnessManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        private const val TAG = "BrightnessManager"
        private const val DEFAULT_BRIGHTNESS = 50
        private const val SYSTEM_BRIGHTNESS_MAX = 255 // Android system max brightness value
    }

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    private var currentWindow: Window? = null
    private var brightnessObserver: ContentObserver? = null
    private var isMonitoring = false

    @Synchronized
    fun startMonitoring(window: Window) {
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring brightness, skipping")
            return
        }

        Log.d(TAG, "Starting brightness monitoring")
        isMonitoring = true
        currentWindow = window

        // Ensure we're on the main thread for window operations
        if (Looper.myLooper() == Looper.getMainLooper()) {
            setupBrightnessObserver(window)
            forceBrightnessUpdate(window)
        } else {
            Handler(Looper.getMainLooper()).post {
                setupBrightnessObserver(window)
                forceBrightnessUpdate(window)
            }
        }
    }

    private fun setupBrightnessObserver(window: Window) {
        brightnessObserver?.let { context.contentResolver.unregisterContentObserver(it) }

        brightnessObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.d(TAG, "System brightness changed. URI: $uri")

                if (prefs.getBoolean(PreferenceKeys.USE_CUSTOM_BRIGHTNESS, false)) {
                    forceBrightnessUpdate(window)
                }
            }
        }.also { observer ->
            try {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    observer
                )
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false,
                    observer
                )
                Log.d(TAG, "Successfully registered content observers")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register content observers", e)
            }
        }
    }

    @Synchronized
    fun stopMonitoring() {
        Log.d(TAG, "Stopping brightness monitoring")
        brightnessObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        brightnessObserver = null
        currentWindow = null
        isMonitoring = false
    }

    private fun forceBrightnessUpdate(window: Window) {
        if (!isMonitoring) {
            Log.d(TAG, "Not monitoring, skipping brightness apply")
            return
        }

        try {
            val brightnessPercent = prefs.getInt(PreferenceKeys.BRIGHTNESS_LEVEL, DEFAULT_BRIGHTNESS)
            val brightness = brightnessPercent / 100f
            Log.d(TAG, "Forcing brightness update to: $brightnessPercent%")

            // First, try to set system brightness if we have permission
            if (Settings.System.canWrite(context)) {
                try {
                    // Force manual mode
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )

                    // Set system brightness (0-255 range)
                    val systemBrightness = (brightnessPercent * SYSTEM_BRIGHTNESS_MAX / 100f).toInt()
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        systemBrightness.coerceIn(0, SYSTEM_BRIGHTNESS_MAX)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set system brightness", e)
                }
            }

            // Then set window brightness
            Handler(Looper.getMainLooper()).post {
                try {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                    val params = window.attributes
                    params.screenBrightness = brightness
                    params.flags = params.flags or (
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                            )
                    window.attributes = params

                    // Verify the change
                    val appliedBrightness = window.attributes.screenBrightness
                    Log.d(TAG, "Verified window brightness after apply: $appliedBrightness")
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating window brightness", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in forceBrightnessUpdate", e)
        }
    }

    fun setBrightness(window: Window, brightnessPercent: Int) {
        Log.d(TAG, "Setting brightness to $brightnessPercent%")
        prefs.edit()
            .putInt(PreferenceKeys.BRIGHTNESS_LEVEL, brightnessPercent)
            .putBoolean(PreferenceKeys.USE_CUSTOM_BRIGHTNESS, true)
            .apply()

        forceBrightnessUpdate(window)
    }

    fun resetBrightness(window: Window) {
        Log.d(TAG, "Resetting to system brightness")
        prefs.edit()
            .putBoolean(PreferenceKeys.USE_CUSTOM_BRIGHTNESS, false)
            .apply()

        if (Settings.System.canWrite(context)) {
            try {
                // Reset to auto mode
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset brightness mode", e)
            }
        }

        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    fun getCurrentBrightness(): Int {
        return prefs.getInt(PreferenceKeys.BRIGHTNESS_LEVEL, DEFAULT_BRIGHTNESS)
    }

    fun isCustomBrightnessEnabled(): Boolean {
        return prefs.getBoolean(PreferenceKeys.USE_CUSTOM_BRIGHTNESS, false)
    }
}