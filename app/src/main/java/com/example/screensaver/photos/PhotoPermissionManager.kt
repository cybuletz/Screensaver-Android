package com.example.screensaver.photos

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.screensaver.PhotoRepository
import com.example.screensaver.shared.GoogleAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoPermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val googleAuthManager: GoogleAuthManager,
    private val photoUriManager: PhotoUriManager
) {
    companion object {
        private const val TAG = "PhotoPermissionManager"
    }

    private val _permissionState = MutableStateFlow<PermissionState>(PermissionState.VALID)
    val permissionState: StateFlow<PermissionState> = _permissionState

    sealed class PermissionState {
        object VALID : PermissionState()
        data class INVALID(val invalidUris: List<String>) : PermissionState()
        object CHECKING : PermissionState()
    }

    private fun getCurrentDateTime(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    suspend fun validatePhotos(lifecycleScope: LifecycleCoroutineScope): Boolean {
        _permissionState.value = PermissionState.CHECKING

        try {
            val photos = photoRepository.getAllPhotos()
            val invalidUris = mutableListOf<String>()

            Log.d(TAG, """Starting photo validation at ${getCurrentDateTime()}
                |• Total photos to check: ${photos.size}
                |• Current user: ${System.getProperty("user.name") ?: "unknown"}""".trimMargin())

            photos.forEach { photo ->
                try {
                    val uri = Uri.parse(photo.baseUrl)
                    if (photoUriManager.isGooglePhotosUri(uri)) {
                        if (!photoUriManager.hasValidPermission(uri)) {
                            invalidUris.add(photo.baseUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error validating photo URI: ${photo.baseUrl}", e)
                    invalidUris.add(photo.baseUrl)
                }
            }

            if (invalidUris.isNotEmpty()) {
                Log.w(TAG, """Found invalid photos at ${getCurrentDateTime()}
                    |• Invalid photos: ${invalidUris.size}
                    |• Total photos: ${photos.size}""".trimMargin())
                _permissionState.value = PermissionState.INVALID(invalidUris)
                return false
            }

            _permissionState.value = PermissionState.VALID
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error validating photos", e)
            _permissionState.value = PermissionState.VALID // Default to valid to prevent blocking
            return true
        }
    }

    fun handleInvalidPermissions(lifecycleScope: LifecycleCoroutineScope, onComplete: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, """Starting permission recovery at ${getCurrentDateTime()}
                    |• Current user: ${System.getProperty("user.name") ?: "unknown"}""".trimMargin())

                // First try to refresh tokens
                if (googleAuthManager.refreshTokens()) {
                    // Revalidate photos after token refresh
                    if (validatePhotos(lifecycleScope)) {
                        onComplete(true)
                        return@launch
                    }
                }

                // If we get here, we need to remove invalid photos
                when (val state = permissionState.value) {
                    is PermissionState.INVALID -> {
                        Log.d(TAG, "Removing ${state.invalidUris.size} invalid photos")
                        state.invalidUris.forEach { uri ->
                            photoRepository.removePhoto(uri)
                        }
                        onComplete(false)
                    }
                    else -> onComplete(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, """Error handling invalid permissions at ${getCurrentDateTime()}
                    |• Error: ${e.message}
                    |• User: ${System.getProperty("user.name") ?: "unknown"}""".trimMargin())
                onComplete(false)
            }
        }
    }
}