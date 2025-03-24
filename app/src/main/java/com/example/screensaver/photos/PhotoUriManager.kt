package com.example.screensaver.photos

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.screensaver.auth.GoogleAuthManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class PhotoUriManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val persistentPhotoCache: PersistentPhotoCache
) {
    companion object {
        private const val TAG = "PhotoUriManager"
        const val URI_TYPE_GOOGLE_PHOTOS = 1
        const val URI_TYPE_PHOTO_PICKER = 2
        const val URI_TYPE_CONTENT = 3
        const val URI_TYPE_FILE = 4
        const val URI_TYPE_OTHER = 5
    }

    fun hasValidPermission(uri: Uri): Boolean {
        // First check if we have a cached version
        val uriString = uri.toString()
        if (persistentPhotoCache.isUriCached(uriString)) {
            return true
        }

        // Otherwise check actual URI permissions
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (isGooglePhotosUri(uri) || isPhotoPickerUri(uri)) {
                // For Google Photos URIs, check if the URI is valid at all
                // Note: This might actually try to access the URI which could fail
                val test = context.contentResolver.query(
                    uri, null, null, null, null
                )
                val hasPermission = test != null
                test?.close()
                hasPermission
            } else {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission check failed for URI: $uri", e)
            false
        }
    }

    fun getUriType(uri: Uri): Int {
        return when {
            isGooglePhotosUri(uri) -> URI_TYPE_GOOGLE_PHOTOS
            isPhotoPickerUri(uri) -> URI_TYPE_PHOTO_PICKER
            uri.scheme == ContentResolver.SCHEME_CONTENT -> URI_TYPE_CONTENT
            uri.scheme == "file" -> URI_TYPE_FILE
            else -> URI_TYPE_OTHER
        }
    }

    private fun isPhotoPickerUri(uri: Uri): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uriString = uri.toString()
            uriString.contains("media/picker") ||
                    uriString.contains("com.android.providers.media.photopicker")
        } else {
            false
        }
    }

    fun isGooglePhotosUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        return uriString.contains("com.google.android.apps.photos") ||
                uriString.contains("googleusercontent.com")
    }

    fun getCachedUriOrOriginal(uri: String): String {
        return persistentPhotoCache.getCachedPhotoUri(uri) ?: uri
    }

    fun validateUris(uris: List<String>): List<String> {
        return uris.filter { uriString ->
            // First check if we have a cached version
            val cachedUri = persistentPhotoCache.getCachedPhotoUri(uriString)
            if (cachedUri != null) {
                // We have a cached version, so this URI is valid
                true
            } else {
                try {
                    // Otherwise check if the original URI is valid
                    val uri = Uri.parse(uriString)
                    hasValidPermission(uri)
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid URI: $uriString", e)
                    false
                }
            }
        }
    }

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

    fun takePersistablePermission(uri: Uri): Boolean {
        return try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take persistable permission: $uri", e)
            false
        }
    }

    data class UriData(
        val uri: String,
        val type: String,
        val hasPersistedPermission: Boolean,
        val timestamp: Long
    )
}