package com.photostreamr.photos

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class PhotoUriManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val persistentPhotoCache: PersistentPhotoCache
) {
    companion object {
        private const val TAG = "PhotoUriManager"
    }

    fun hasValidPermission(uri: Uri): Boolean {
        val uriString = uri.toString()

        // For Google Photos URIs - check only cache
        if (isGooglePhotosUri(uri)) {
            val isCached = persistentPhotoCache.isUriCached(uriString)
            Log.d(TAG, "Google Photos URI $uriString - cached: $isCached")
            return isCached
        }

        // For local files - check if file exists
        if (uri.scheme == "content" || uri.scheme == "file") {
            try {
                // For content URIs, try to open the file
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    Log.d(TAG, "Local file URI $uriString - exists: true")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Local file URI $uriString - exists: false (${e.message})")
                return false
            }
        }

        Log.d(TAG, "Unknown URI scheme ${uri.scheme} for $uriString")
        return false
    }

    fun isGooglePhotosUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        return uriString.contains("com.google.android.apps.photos") ||
                uriString.contains("googleusercontent.com")
    }

    data class UriData(
        val uri: String,
        val type: String,
        val hasPersistedPermission: Boolean,
        val timestamp: Long
    )
}