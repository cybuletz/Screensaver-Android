package com.example.screensaver.photos

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.screensaver.shared.GoogleAuthManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages photo URIs across different Android versions, handling persistence,
 * validation, and version-specific behaviors for photo URIs.
 */
@Singleton
class PhotoUriManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val googleAuthManager: GoogleAuthManager
) {
    companion object {
        private const val TAG = "PhotoUriManager"
        private const val URI_EXPIRY_CHECK_THRESHOLD = 86400000L // 24 hours in milliseconds

        // Constants for URI types
        const val URI_TYPE_PHOTO_PICKER = "photo_picker"
        const val URI_TYPE_GOOGLE_PHOTOS = "google_photos"
        const val URI_TYPE_CONTENT = "content"
        const val URI_TYPE_FILE = "file"
        const val URI_TYPE_UNKNOWN = "unknown"

        // Permission flags
        const val PERMISSION_FLAGS_READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val PERMISSION_FLAGS_READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        // Android version-specific constants
        const val ANDROID_11_MAX_RETRY_COUNT = 3
    }

    /**
     * Get a specialized intent specifically for Google Photos
     */
    fun getGooglePhotosIntent(allowMultiple: Boolean = true): Intent {
        // Special handling for Android 11
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            return Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                putExtra("android.content.extra.SHOW_ADVANCED", true)

                // Request ALL possible permissions
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )

                // Target Google Photos specifically
                `package` = "com.google.android.apps.photos"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.google.android.apps.photos.contentprovider"))
            }
        }

        // Default implementation for other Android versions
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)

            // Request ALL possible permissions
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )

            // Target Google Photos specifically
            `package` = "com.google.android.apps.photos"
            putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.google.android.apps.photos.contentprovider"))
        }

        return intent
    }

    /**
     * Take persistable permissions for a URI with proper error handling
     * @param uri The URI to take permissions for
     * @param flags Permission flags to request
     * @return true if permissions were successfully taken
     */
    fun takePersistablePermission(uri: Uri, flags: Int = PERMISSION_FLAGS_READ_WRITE): Boolean {
        try {
            val resolver = context.contentResolver

            // Check if URI requires special handling
            if (isGooglePhotosUri(uri)) {
                // For Google Photos URIs on Android 11+, we need specific handling
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    try {
                        // Try to take persistable permission with specific flags for Android 11
                        resolver.takePersistableUriPermission(uri, PERMISSION_FLAGS_READ)

                        // Record successful permission
                        preferences.addRecentlyAccessedUri(uri.toString())

                        // Additional validation for Android 11
                        if (!isUriAccessible(uri)) {
                            Log.w(TAG, "Permission taken but URI still not accessible: $uri")
                            preferences.addRecentlyAccessedUri(uri.toString())
                            return true  // Still return true to prevent repeated attempts
                        }

                        return true
                    } catch (se: SecurityException) {
                        // Special handling for Android 11
                        Log.w(TAG, "Android 11 Security Exception taking permission: ${se.message}")

                        // Record this URI even if permission failed
                        preferences.addRecentlyAccessedUri(uri.toString())

                        // Try different flags if the first attempt failed
                        try {
                            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            return true
                        } catch (e: Exception) {
                            Log.w(TAG, "Second attempt failed: ${e.message}")
                            return false
                        }
                    }
                } else {
                    // For Android 12+
                    try {
                        resolver.takePersistableUriPermission(uri, PERMISSION_FLAGS_READ)
                        preferences.addRecentlyAccessedUri(uri.toString())
                        return true
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not take persistable permission: ${e.message}")
                        preferences.addRecentlyAccessedUri(uri.toString())
                        return false
                    }
                }
            } else {
                // Standard URIs - try to take persistable permission
                resolver.takePersistableUriPermission(uri, flags)
                return true
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable permission for $uri: ${e.message}")

            // Even if we can't take persistable permission, record access for later
            preferences.addRecentlyAccessedUri(uri.toString())
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error taking persistable permission", e)
            return false
        }
    }

    fun hasValidPermission(uri: Uri): Boolean {
        try {
            val resolver = context.contentResolver

            // For Google Photos URIs, check if we've accessed them recently
            if (isGooglePhotosUri(uri)) {
                // For Android 11, additional validation required
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    val isRecent = preferences.getRecentlyAccessedUris().contains(uri.toString())
                    if (!isRecent) return false

                    // Try to validate with a lightweight check
                    try {
                        return isUriAccessible(uri)
                    } catch (e: Exception) {
                        Log.w(TAG, "URI access check failed, will try to refresh: $uri")
                        takePersistablePermission(uri)
                        return preferences.getRecentlyAccessedUris().contains(uri.toString())
                    }
                }
                return preferences.getRecentlyAccessedUris().contains(uri.toString())
            }

            // For other URIs, check if we have persistable permissions
            return resolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URI permission", e)
            return false
        }
    }

    fun getUriType(uri: Uri): String {
        return when {
            uri.toString().contains("com.android.providers.media.photopicker") -> {
                URI_TYPE_PHOTO_PICKER
            }
            uri.toString().contains("com.google.android.apps.photos") -> {
                URI_TYPE_GOOGLE_PHOTOS
            }
            uri.toString().contains("googleusercontent.com") -> {
                URI_TYPE_GOOGLE_PHOTOS
            }
            uri.scheme == ContentResolver.SCHEME_CONTENT -> {
                URI_TYPE_CONTENT
            }
            uri.scheme == ContentResolver.SCHEME_FILE -> {
                URI_TYPE_FILE
            }
            else -> URI_TYPE_UNKNOWN
        }
    }

    fun isGooglePhotosUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        return uriString.contains("com.google.android.apps.photos") ||
                uriString.contains("googleusercontent.com")
    }

    suspend fun validateUris(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.filter { uriString ->
            try {
                val uri = Uri.parse(uriString)
                when (getUriType(uri)) {
                    URI_TYPE_PHOTO_PICKER -> {
                        // Photo picker URIs from Android 13+ should be persistently accessible
                        true
                    }
                    URI_TYPE_GOOGLE_PHOTOS -> {
                        // Google Photos URIs need special handling
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                            // For Android 11, we need to do special validation
                            val isValid = hasValidPermission(uri)

                            if (!isValid && preferences.getRecentlyAccessedUris().contains(uriString)) {
                                // Try to refresh permission
                                takePersistablePermission(uri)
                                hasValidPermission(uri) || isUriAccessible(uri)
                            } else {
                                isValid
                            }
                        } else {
                            hasValidPermission(uri)
                        }
                    }
                    URI_TYPE_CONTENT -> {
                        // For general content URIs, check if we still have permission
                        hasValidPermission(uri) && isUriAccessible(uri)
                    }
                    else -> {
                        // For other URI types, just check if they're accessible
                        isUriAccessible(uri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error validating URI: $uriString", e)

                // For Android 11, make one special last attempt for Google Photos URIs
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    try {
                        val uri = Uri.parse(uriString)
                        if (isGooglePhotosUri(uri) && preferences.getRecentlyAccessedUris().contains(uriString)) {
                            takePersistablePermission(uri)
                            return@filter isUriAccessible(uri)
                        }
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "Retry attempt failed", retryEx)
                    }
                }

                false
            }
        }
    }

    /**
     * Check if a URI can be accessed (read)
     */
    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            // For Android 11 and Google Photos URIs, use a more reliable check
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && isGooglePhotosUri(uri)) {
                context.contentResolver.openInputStream(uri)?.use {
                    // Just check if we can open it
                    true
                } ?: false
            } else {
                // Standard check for other URIs
                context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { cursor ->
                    cursor.count > 0
                } ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URI accessibility: $uri", e)
            false
        }
    }

    suspend fun processSelectedUris(uris: List<Uri>): List<UriData> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            try {
                // For Android 11 and Google Photos URIs, try with extra care
                val permissionTaken = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && isGooglePhotosUri(uri)) {
                    // Try multiple times for Android 11
                    var success = false
                    for (attempt in 1..ANDROID_11_MAX_RETRY_COUNT) {
                        success = takePersistablePermission(uri)
                        if (success) break

                        // Add a short delay between attempts
                        kotlinx.coroutines.delay(100)
                    }

                    // Even if permissions failed, still save the URI
                    if (!success) {
                        preferences.addRecentlyAccessedUri(uri.toString())
                    }

                    true  // Optimistically return true to ensure the URI is saved
                } else {
                    // Normal permission taking for other versions/URI types
                    takePersistablePermission(uri)
                }

                // Create URI metadata
                UriData(
                    uri = uri.toString(),
                    type = getUriType(uri),
                    hasPersistedPermission = permissionTaken,
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing URI: $uri", e)

                // For Android 11, try to salvage the URI by recording it
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    try {
                        preferences.addRecentlyAccessedUri(uri.toString())
                        return@mapNotNull UriData(
                            uri = uri.toString(),
                            type = getUriType(uri),
                            hasPersistedPermission = false,
                            timestamp = System.currentTimeMillis()
                        )
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "Failed to salvage URI", retryEx)
                    }
                }

                null
            }
        }
    }

    fun getPhotoPickerIntent(allowMultiple: Boolean = true): Intent {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ (API 33+): Use system photo picker
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (allowMultiple) 100 else 1)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.R && googleAuthManager.hasValidTokens() -> {
                // Android 11 with Google auth: Special case with extra flags
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    putExtra("android.content.extra.SHOW_ADVANCED", true)

                    // Add ALL possible permission flags
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && googleAuthManager.hasValidTokens() -> {
                // Android 12 with Google auth: Try Google Photos
                getGooglePhotosIntent(allowMultiple)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12 without Google auth: Use ACTION_OPEN_DOCUMENT
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
            }
            else -> {
                // Fallback for older versions
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    suspend fun processUri(uri: Uri): UriData? = withContext(Dispatchers.IO) {
        try {
            // For Google Photos URIs, ensure we have valid auth
            if (isGooglePhotosUri(uri) && !googleAuthManager.hasValidTokens()) {
                if (!googleAuthManager.refreshTokens()) {
                    return@withContext null
                }
            }

            // Special handling for Android 11 Google Photos URIs
            val hasPermission = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && isGooglePhotosUri(uri)) {
                // Try multiple times for Android 11
                var success = false
                for (attempt in 1..ANDROID_11_MAX_RETRY_COUNT) {
                    success = takePersistablePermission(uri)
                    if (success) break

                    // Add a short delay between attempts
                    kotlinx.coroutines.delay(100)
                }

                // Always record the URI in preferences, regardless of permission success
                preferences.addRecentlyAccessedUri(uri.toString())

                true  // Return true optimistically for Android 11
            } else {
                takePersistablePermission(uri)
            }

            val timestamp = System.currentTimeMillis()
            val uriType = getUriType(uri)

            if (hasPermission) {
                preferences.addRecentlyAccessedUri(uri.toString())
            }

            UriData(
                uri = uri.toString(),
                type = uriType,
                hasPersistedPermission = hasPermission,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing URI: $uri", e)

            // For Android 11, try to salvage the URI
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                try {
                    preferences.addRecentlyAccessedUri(uri.toString())
                    return@withContext UriData(
                        uri = uri.toString(),
                        type = getUriType(uri),
                        hasPersistedPermission = false,
                        timestamp = System.currentTimeMillis()
                    )
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Failed to salvage URI", retryEx)
                }
            }

            null
        }
    }

    fun validateUri(uri: Uri): Boolean {
        return try {
            // Special case for Android 11 Google Photos URIs
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && isGooglePhotosUri(uri)) {
                if (preferences.getRecentlyAccessedUris().contains(uri.toString())) {
                    try {
                        val isAccessible = isUriAccessible(uri)
                        if (!isAccessible) {
                            // If not accessible but in recent list, try to refresh permission
                            takePersistablePermission(uri)
                            return isUriAccessible(uri) || preferences.getRecentlyAccessedUris().contains(uri.toString())
                        }
                        return true
                    } catch (e: Exception) {
                        // Even if checking accessibility fails, return true if it's in the accessed list
                        return preferences.getRecentlyAccessedUris().contains(uri.toString())
                    }
                }
                return false
            }

            // Standard validation for other cases
            when (getUriType(uri)) {
                URI_TYPE_PHOTO_PICKER -> {
                    // For photo picker URIs, check if we have persisted permission
                    context.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission
                    }
                }
                URI_TYPE_CONTENT -> {
                    // For content URIs, try to query metadata
                    context.contentResolver.query(uri,
                        arrayOf(MediaStore.Images.Media._ID),
                        null, null, null)?.use { cursor ->
                        cursor.count > 0
                    } ?: false
                }
                else -> {
                    // For all other URIs, check if recently accessed
                    preferences.getRecentlyAccessedUris().contains(uri.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating URI: $uri", e)
            // For Android 11, fall back to preferences check
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                return preferences.getRecentlyAccessedUris().contains(uri.toString())
            }
            false
        }
    }

    /**
     * Refresh permissions for all saved URIs
     * This can help recover permissions, especially on Android 11
     */
    suspend fun refreshAllPermissions() = withContext(Dispatchers.IO) {
        val recentUris = preferences.getRecentlyAccessedUris()
        Log.d(TAG, "Attempting to refresh permissions for ${recentUris.size} URIs")

        var successCount = 0
        var failCount = 0

        recentUris.forEach { uriString ->
            try {
                val uri = Uri.parse(uriString)
                if (isGooglePhotosUri(uri)) {
                    val success = takePersistablePermission(uri)
                    if (success) successCount++ else failCount++
                }
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Failed to refresh permission for $uriString", e)
            }
        }

        Log.d(TAG, "Permission refresh complete - Success: $successCount, Failed: $failCount")
    }

    /**
     * Data class to store metadata about URIs
     */
    data class UriData(
        val uri: String,
        val type: String,
        val hasPersistedPermission: Boolean,
        val timestamp: Long
    )
}