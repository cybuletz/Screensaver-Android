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
import com.example.screensaver.utils.PreferenceKeys
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.screensaver.lock.PhotoLockScreenService

/**
 * Device administrator receiver for handling lock screen permissions and management.
 */
class PhotoLockDeviceAdmin : DeviceAdminReceiver() {
    @Inject
    lateinit var kioskPolicyManager: KioskPolicyManager

    companion object {
        private const val TAG = "PhotoLockDeviceAdmin"

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, PhotoLockDeviceAdmin::class.java)

        fun isAdminActive(context: Context): Boolean =
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                ?.isAdminActive(getComponentName(context)) == true
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)

        // If kiosk mode is enabled, set up kiosk policies
        val isKioskEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("kiosk_mode_enabled", false)

        if (isKioskEnabled) {
            KioskPolicyManager(context).setKioskPolicies(true)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        handleAdminStateChange(context, false) {
            "Device admin disabled successfully"
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        logEvent("Device password changed")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        logEvent("Lock task mode entering for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        logEvent("Lock task mode exiting")
    }

    private fun handleAdminStateChange(context: Context, isEnabled: Boolean, logMessage: () -> String) {
        try {
            updateAdminState(context, isEnabled)
            logEvent(logMessage())
            showToast(context, if (isEnabled) R.string.device_admin_enabled else R.string.device_admin_disabled)

            if (isEnabled) {
                startLockScreenService(context)
            } else {
                stopLockScreenService(context)
                updateDisplayModePreference(context)
            }
        } catch (e: Exception) {
            handleError(context, "Error managing device admin state", e)
        }
    }

    private fun updateAdminState(context: Context, isEnabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PreferenceKeys.DEVICE_ADMIN_ACTIVE, isEnabled)
            .apply()
    }

    private fun updateDisplayModePreference(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(PreferenceKeys.DISPLAY_MODE, PreferenceKeys.DISPLAY_MODE_DREAM)
            .apply()
    }

    private fun startLockScreenService(context: Context) {
        safeServiceOperation(context) {
            context.startService(Intent(context, PhotoLockScreenService::class.java))
        }
    }

    private fun stopLockScreenService(context: Context) {
        safeServiceOperation(context) {
            context.stopService(Intent(context, PhotoLockScreenService::class.java))
        }
    }

    private fun safeServiceOperation(context: Context, operation: () -> Unit) {
        try {
            operation()
        } catch (e: Exception) {
            handleError(context, "Error managing lock screen service", e)
        }
    }

    private fun showToast(context: Context, messageResId: Int) {
        try {
            Toast.makeText(
                context.applicationContext,
                messageResId,
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing toast", e)
        }
    }

    private fun handleError(context: Context, message: String, error: Exception) {
        Log.e(TAG, message, error)
        showToast(context, R.string.device_admin_error)
    }

    private fun logEvent(message: String) {
        Log.d(TAG, message)
    }
}