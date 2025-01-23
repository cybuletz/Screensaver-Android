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
import com.example.screensaver.receivers.PhotoLockDeviceAdmin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages all permission-related operations for the application.
 * Handles permission requests, checks, and status tracking.
 */
class PermissionManager(private val context: Context) {

    private val _permissionState = MutableStateFlow(PermissionState())
    private var permissionCallback: ((Boolean) -> Unit)? = null
    private lateinit var deviceAdmin: ComponentName

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

        private const val REQUEST_DEVICE_ADMIN = 1
        private const val REQUEST_OVERLAY_PERMISSION = 2
    }

    /**
     * Represents the current state of all required permissions
     */
    data class PermissionState(
        val storagePermissionGranted: Boolean = false,
        val networkPermissionGranted: Boolean = false,
        val deviceAdminActive: Boolean = false,
        val overlayPermissionGranted: Boolean = false,
        val notificationPermissionGranted: Boolean = false
    )

    init {
        deviceAdmin = ComponentName(context, PhotoLockDeviceAdmin::class.java)
        updatePermissionState()
    }

    /**
     * Provides access to the current permission state
     */
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    /**
     * Updates the current state of all permissions
     */
    private fun updatePermissionState() {
        _permissionState.value = PermissionState(
            storagePermissionGranted = checkStoragePermission(),
            networkPermissionGranted = checkNetworkPermission(),
            deviceAdminActive = isDeviceAdminActive(),
            overlayPermissionGranted = checkOverlayPermission(),
            notificationPermissionGranted = checkNotificationPermission()
        )
    }

    /**
     * Registers permission launchers for an activity
     */
    fun registerWithActivity(activity: FragmentActivity): Map<String, ActivityResultLauncher<String>> {
        val launchers = mutableMapOf<String, ActivityResultLauncher<String>>()

        // Storage permission launcher
        launchers["storage"] = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            handlePermissionResult(isGranted)
        }

        // Notification permission launcher (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launchers["notification"] = activity.registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                handlePermissionResult(isGranted)
            }
        }

        return launchers
    }

    /**
     * Checks if all required permissions are granted
     */
    fun areAllPermissionsGranted(): Boolean {
        return permissionState.value.run {
            storagePermissionGranted && networkPermissionGranted &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionGranted)
        }
    }

    /**
     * Requests all required permissions
     */
    fun requestRequiredPermissions(
        activity: Activity,
        callback: (Boolean) -> Unit
    ) {
        permissionCallback = callback

        val ungrantedPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (ungrantedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                ungrantedPermissions,
                PackageManager.PERMISSION_GRANTED
            )
        } else {
            callback(true)
        }
    }

    /**
     * Handles permission request results
     */
    private fun handlePermissionResult(isGranted: Boolean) {
        updatePermissionState()
        permissionCallback?.invoke(isGranted)
        permissionCallback = null
    }

    /**
     * Checks storage permission
     */
    private fun checkStoragePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks network permission
     */
    private fun checkNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_NETWORK_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks notification permission
     */
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

    /**
     * Checks if device admin is active
     */
    fun isDeviceAdminActive(): Boolean {
        val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return devicePolicyManager.isAdminActive(deviceAdmin)
    }

    /**
     * Requests device admin privileges
     */
    fun requestDeviceAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdmin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device admin permission is required for lock screen functionality"
            )
        }
        activity.startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
    }

    /**
     * Checks overlay permission
     */
    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Requests overlay permission
     */
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    /**
     * Shows permission settings
     */
    fun openPermissionSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        activity.startActivity(intent)
    }

    /**
     * Handles activity results for permission requests
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int) {
        when (requestCode) {
            REQUEST_DEVICE_ADMIN -> {
                updatePermissionState()
            }
            REQUEST_OVERLAY_PERMISSION -> {
                updatePermissionState()
            }
        }
    }
}