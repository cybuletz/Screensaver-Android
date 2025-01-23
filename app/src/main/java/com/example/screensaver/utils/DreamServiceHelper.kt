package com.example.screensaver.utils

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.content.Intent
import android.service.dreams.DreamService

/**
 * Helper class for managing Dream Service (screensaver) functionality
 */
class DreamServiceHelper(
    private val context: Context,
    private val dreamServiceComponent: ComponentName
) {
    companion object {
        private const val TAG = "DreamServiceHelper"

        /**
         * Creates a new instance of DreamServiceHelper
         */
        fun create(context: Context, serviceClass: Class<out DreamService>): DreamServiceHelper {
            return DreamServiceHelper(
                context,
                ComponentName(context, serviceClass)
            )
        }
    }

    /**
     * Checks if the Dream API is available on this device
     */
    fun isDreamApiAvailable(): Boolean {
        return try {
            // Check if we're running on a supported API level
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
                Log.d(TAG, "Dream API not available - API level too low")
                return false
            }

            // Verify DreamService class is available
            val dreamServiceClass = Class.forName("android.service.dreams.DreamService")
            Log.d(TAG, "Dream API available - DreamService class found")
            dreamServiceClass != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Dream API availability", e)
            false
        }
    }

    /**
     * Gets the list of available dream components
     */
    fun getDreamComponents(): List<ComponentName> {
        return try {
            listOf(dreamServiceComponent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dream components", e)
            emptyList()
        }
    }

    /**
     * Checks if the dream service is currently active
     */
    fun isDozing(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                powerManager?.isInteractive?.not() ?: false
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check dozing state", e)
            false
        }
    }

    /**
     * Opens the system dream settings
     */
    fun openDreamSettings() {
        try {
            val intent = Intent(Settings.ACTION_DREAM_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "Opened system dream settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open dream settings", e)
            // Fallback for some devices
            try {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Opened display settings as fallback")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open display settings", e)
            }
        }
    }

    /**
     * Checks if this dream service is currently set as active
     */
    fun isDreamServiceEnabled(): Boolean {
        return try {
            val componentString = dreamServiceComponent.flattenToString()
            Settings.Secure.getString(
                context.contentResolver,
                "screensaver_components"
            )?.contains(componentString) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if dream service is enabled", e)
            false
        }
    }

    /**
     * Gets the current dream service configuration status
     */
    fun getDreamServiceStatus(): DreamServiceStatus {
        return try {
            when {
                !isDreamApiAvailable() -> DreamServiceStatus.API_UNAVAILABLE
                !isDreamServiceEnabled() -> DreamServiceStatus.NOT_SELECTED
                isDozing() -> DreamServiceStatus.ACTIVE
                else -> DreamServiceStatus.CONFIGURED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get dream service status", e)
            DreamServiceStatus.UNKNOWN
        }
    }
}

/**
 * Enum representing the possible states of the dream service
 */
enum class DreamServiceStatus {
    API_UNAVAILABLE,  // Dream API not available on this device
    NOT_SELECTED,     // Dream Service is not selected in system settings
    CONFIGURED,       // Dream Service is selected but not currently active
    ACTIVE,          // Dream Service is currently active
    UNKNOWN          // Status could not be determined
}