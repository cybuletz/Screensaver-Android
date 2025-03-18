package com.example.screensaver.photos

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages photo URIs across different Android versions, handling persistence,
 * validation, and version-specific behaviors for photo URIs.
 */
@Singleton
class PhotoUriManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences
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
    }

    /**
     * Get a specialized intent specifically for Google Photos
     */
    fun getGooglePhotosIntent(allowMultiple: Boolean = true): Intent {
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
     * Take persistable permissions for a URI with appropriate error handling
     * @return true if permissions were successfully taken, false otherwise
     */
    fun takePersistablePermission(uri: Uri, flags: Int = PERMISSION_FLAGS_READ_WRITE): Boolean {
        try {
            val resolver = context.contentResolver

            // Check if URI requires special handling
            if (isGooglePhotosUri(uri)) {
                // For Google Photos URIs on Android 11+, we need specific handling
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Try to take persistable permission with specific flags
                    resolver.takePersistableUriPermission(uri, PERMISSION_FLAGS_READ)
                }

                // Record this URI for future access
                preferences.addRecentlyAccessedUri(uri.toString())
                return true
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

    /**
     * Verify if we still have valid permission to access a URI
     */
    fun hasValidPermission(uri: Uri): Boolean {
        try {
            val resolver = context.contentResolver

            // For Google Photos URIs, check if we've accessed them recently
            if (isGooglePhotosUri(uri)) {
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

    /**
     * Check the type of a URI to determine its source and handling requirements
     */
    fun getUriType(uri: Uri): String {
        return when {
            uri.toString().contains("com.android.providers.media.photopicker") -> {
                URI_TYPE_PHOTO_PICKER
            }
            uri.toString().contains("com.google.android.apps.photos") -> {
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

    /**
     * Check if a URI is from Google Photos
     */
    fun isGooglePhotosUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        return uriString.contains("com.google.android.apps.photos") ||
                uriString.contains("googleusercontent.com")
    }

    /**
     * Validate a collection of URIs and filter out ones that are no longer accessible
     */
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
                        hasValidPermission(uri)
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
                false
            }
        }
    }

    /**
     * Check if a URI is physically accessible by trying to query its metadata
     */
    private fun isUriAccessible(uri: Uri): Boolean {
        return try {
            context.contentResolver.query(uri, arrayOf("_id"), null, null, null)?.use { cursor ->
                cursor.count > 0
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URI accessibility: $uri", e)
            false
        }
    }

    /**
     * Process a collection of newly selected URIs
     * @return List of URIs that were successfully processed and should be saved
     */
    suspend fun processSelectedUris(uris: List<Uri>): List<UriData> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            try {
                // First try to take persistable permissions
                val permissionTaken = takePersistablePermission(uri)

                // Create URI metadata
                UriData(
                    uri = uri.toString(),
                    type = getUriType(uri),
                    hasPersistedPermission = permissionTaken,
                    timestamp = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing URI: $uri", e)
                null
            }
        }
    }

    /**
     * Get the appropriate photo picker intent based on Android version (11-14)
     * @param allowMultiple Whether to allow multiple photo selection
     * @return Intent configured for the appropriate photo picker
     */
    fun getPhotoPickerIntent(allowMultiple: Boolean = true): Intent {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                // Android 13+ (API 33+): Use system photo picker
                Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    type = "image/*"
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, if (allowMultiple) 100 else 1)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                // Android 11-12 (API 30-32): Use ACTION_OPEN_DOCUMENT
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

                    // Add preferred initial URI
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
            }
            else -> {
                // Fallback for other versions
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    /**
     * Process selected URIs and ensure proper permissions
     * @param uri The URI to process
     * @return UriData object with metadata if successful, null if failed
     */
    suspend fun processUri(uri: Uri): UriData? = withContext(Dispatchers.IO) {
        try {
            // Take persistable permission immediately
            val hasPermission = takePersistablePermission(uri)

            // Create metadata
            val timestamp = System.currentTimeMillis()
            val uriType = getUriType(uri)

            // Store in preferences for backup
            if (hasPermission) {
                preferences.addRecentlyAccessedUri(uri.toString())
            }

            return@withContext UriData(
                uri = uri.toString(),
                type = uriType,
                hasPersistedPermission = hasPermission,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error processing URI: $uri", e)
            null
        }
    }

    /**
     * Take persistable permissions for a URI with proper error handling
     * @param uri The URI to take permissions for
     * @return true if permissions were successfully taken
     */
    fun takePersistablePermission(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (e: SecurityException) {
            // If we can't take persistable permission, try to keep temporary
            Log.w(TAG, "Could not take persistable permission: ${e.message}")
            preferences.addRecentlyAccessedUri(uri.toString())
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error taking permission: ${e.message}")
            false
        }
    }

    /**
     * Validate if a URI is still accessible
     * @param uri The URI to validate
     * @return true if the URI is accessible
     */
    fun validateUri(uri: Uri): Boolean {
        return try {
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
            false
        }
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