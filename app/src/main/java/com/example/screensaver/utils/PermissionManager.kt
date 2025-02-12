package com.example.screensaver.utils

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages all permission-related operations for the application.
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _permissionState = MutableStateFlow(PermissionState())
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val deviceAdmin: ComponentName

    companion object {
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.INTERNET
            )
        }
    }

    data class PermissionState(
        val storagePermissionGranted: Boolean = false,
        val networkPermissionGranted: Boolean = false,
        val deviceAdminActive: Boolean = false,
        val overlayPermissionGranted: Boolean = false,
        val notificationPermissionGranted: Boolean = false
    ) {
        val allPermissionsGranted: Boolean
            get() = storagePermissionGranted && networkPermissionGranted &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionGranted)
    }

    init {
        deviceAdmin = ComponentName(context, PhotoLockDeviceAdmin::class.java)
        updatePermissionState()
    }

    private fun updatePermissionState() {
        _permissionState.value = PermissionState(
            storagePermissionGranted = checkStoragePermission(),
            networkPermissionGranted = checkNetworkPermission(),
            deviceAdminActive = isDeviceAdminActive(),
            overlayPermissionGranted = checkOverlayPermission(),
            notificationPermissionGranted = checkNotificationPermission()
        )
    }

    private fun handlePermissionResult(isGranted: Boolean) {
        updatePermissionState()
        permissionCallback?.invoke(isGranted)
        permissionCallback = null
    }

    private fun checkStoragePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isDeviceAdminActive(): Boolean {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return devicePolicyManager.isAdminActive(deviceAdmin)
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}