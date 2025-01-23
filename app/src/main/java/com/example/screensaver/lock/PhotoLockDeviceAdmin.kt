package com.example.screensaver.lock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.example.screensaver.R

/**
 * Device administrator receiver for handling lock screen permissions and management.
 * This receiver is responsible for managing device admin privileges required for
 * custom lock screen functionality.
 */
class PhotoLockDeviceAdmin : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "PhotoLockDeviceAdmin"
        private const val PREF_ADMIN_ACTIVE = "device_admin_active"

        /**
         * Gets the ComponentName for this DeviceAdmin
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, PhotoLockDeviceAdmin::class.java)
        }

        /**
         * Checks if the device admin is currently active
         */
        fun isAdminActive(context: Context): Boolean {
            val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return devicePolicyManager?.isAdminActive(getComponentName(context)) == true
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        try {
            Log.i(TAG, "Device admin enabled")
            updateAdminState(context, true)
            showToast(context, R.string.device_admin_enabled)
            startLockScreenService(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling device admin", e)
            showToast(context, R.string.device_admin_error)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        try {
            Log.i(TAG, "Device admin disabled")
            updateAdminState(context, false)
            showToast(context, R.string.device_admin_disabled)
            stopLockScreenService(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling device admin", e)
            showToast(context, R.string.device_admin_error)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.device_admin_disable_warning)
    }

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.d(TAG, "Device password changed")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock task mode entering for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock task mode exiting")
    }

    private fun updateAdminState(context: Context, isEnabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_ADMIN_ACTIVE, isEnabled)
            .apply()

        // Update display mode preference if admin is disabled
        if (!isEnabled) {
            updateDisplayModePreference(context)
        }
    }

    private fun updateDisplayModePreference(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("display_mode_selection", "dream_service")
            .apply()
    }

    private fun startLockScreenService(context: Context) {
        try {
            Intent(context, PhotoLockScreenService::class.java).also { intent ->
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting lock screen service", e)
        }
    }

    private fun stopLockScreenService(context: Context) {
        try {
            Intent(context, PhotoLockScreenService::class.java).also { intent ->
                context.stopService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock screen service", e)
        }
    }

    private fun showToast(context: Context, messageResId: Int) {
        try {
            Toast.makeText(context.applicationContext, messageResId, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast", e)
        }
    }

    /**
     * Extension function to get the policy manager for the device
     */
    private fun Context.getDevicePolicyManager(): DevicePolicyManager? {
        return getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    }
}