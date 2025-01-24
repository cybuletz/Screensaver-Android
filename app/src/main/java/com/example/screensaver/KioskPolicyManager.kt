package com.example.screensaver.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
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
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            return
        }

        if (enable) {
            setupKioskMode()
        } else {
            disableKioskMode()
        }
    }

    private fun setupKioskMode() {
        try {
            // Set lock task packages
            devicePolicyManager.setLockTaskPackages(
                adminComponent,
                arrayOf(context.packageName)
            )

            // Disable keyguard and status bar
            devicePolicyManager.setKeyguardDisabled(adminComponent, true)
            devicePolicyManager.setStatusBarDisabled(adminComponent, true)

            // Disable features that could allow escape from kiosk mode
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME)
        } catch (e: SecurityException) {
            // Handle the case where we don't have sufficient permissions
        }
    }

    private fun disableKioskMode() {
        try {
            // Clear lock task packages
            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf())

            // Re-enable keyguard and status bar
            devicePolicyManager.setKeyguardDisabled(adminComponent, false)
            devicePolicyManager.setStatusBarDisabled(adminComponent, false)

            // Remove restrictions
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME)
        } catch (e: SecurityException) {
            // Handle the case where we don't have sufficient permissions
        }
    }

    fun isKioskModeAllowed(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent) &&
                devicePolicyManager.getLockTaskPackages(adminComponent).contains(context.packageName)
    }

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }
}