package com.example.screensaver.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KioskPolicyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val devicePolicyManager: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, PhotoLockDeviceAdmin::class.java)

    fun setKioskPolicies(enable: Boolean) {
        if (!checkPrerequisites()) {
            Log.e(TAG, "Prerequisites not met for kiosk mode")
            return
        }

        try {
            if (enable) {
                setupKioskMode()
            } else {
                disableKioskMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting kiosk policies: ${e.message}", e)
        }
    }

    private fun checkPrerequisites(): Boolean {
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            Log.e(TAG, "Device admin is not active")
            return false
        }

        if (context.checkSelfPermission(android.Manifest.permission.MANAGE_DEVICE_POLICY_LOCK_TASK)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing MANAGE_DEVICE_POLICY_LOCK_TASK permission")
            return false
        }

        return true
    }

    private fun setupKioskMode() {
        try {
            // Set lock task packages
            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                arrayOf(context.packageName)
            )
            Log.d(TAG, "Lock task packages set successfully")

            // Configure system settings
            configureSystemSettings(true)

            // Set user restrictions
            setUserRestrictions(true)

            Log.i(TAG, "Kiosk mode setup completed successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during kiosk mode setup: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during kiosk mode setup: ${e.message}", e)
            throw e
        }
    }

    private fun disableKioskMode() {
        try {
            // Clear lock task packages
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())
            Log.d(TAG, "Lock task packages cleared")

            // Reset system settings
            configureSystemSettings(false)

            // Clear user restrictions
            setUserRestrictions(false)

            Log.i(TAG, "Kiosk mode disabled successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during kiosk mode disable: ${e.message}", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during kiosk mode disable: ${e.message}", e)
            throw e
        }
    }

    private fun configureSystemSettings(enable: Boolean) {
        try {
            devicePolicyManager.setKeyguardDisabled(adminComponent, enable)
            devicePolicyManager.setStatusBarDisabled(adminComponent, enable)
            Log.d(TAG, "System settings configured: keyguard and status bar ${if (enable) "disabled" else "enabled"}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception configuring system settings: ${e.message}", e)
            throw e
        }
    }

    private fun setUserRestrictions(enable: Boolean) {
        val restrictions = arrayOf(
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_ADJUST_VOLUME
        )

        try {
            restrictions.forEach { restriction ->
                if (enable) {
                    devicePolicyManager.addUserRestriction(adminComponent, restriction)
                } else {
                    devicePolicyManager.clearUserRestriction(adminComponent, restriction)
                }
            }
            Log.d(TAG, "User restrictions ${if (enable) "set" else "cleared"} successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception setting user restrictions: ${e.message}", e)
            throw e
        }
    }

    fun isKioskModeAllowed(): Boolean {
        if (!checkPrerequisites()) {
            return false
        }

        return try {
            isLockTaskPermitted()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking kiosk mode status: ${e.message}", e)
            false
        }
    }

    private fun isLockTaskPermitted(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val packages = devicePolicyManager.getLockTaskPackages(adminComponent)
                val isPermitted = packages.contains(context.packageName)
                Log.d(TAG, "Lock task permitted (API ${Build.VERSION.SDK_INT}): $isPermitted")
                isPermitted
            } else {
                val isPermitted = devicePolicyManager.isLockTaskPermitted(context.packageName)
                Log.d(TAG, "Lock task permitted (Legacy API): $isPermitted")
                isPermitted
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking lock task permission: ${e.message}", e)
            false
        }
    }

    fun isDeviceOwner(): Boolean {
        return try {
            val isOwner = devicePolicyManager.isDeviceOwnerApp(context.packageName)
            Log.d(TAG, "Device owner check: $isOwner")
            isOwner
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device owner status: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "KioskPolicyManager"
    }
}