package com.example.screensaver.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir = File(context.cacheDir, "photos")
    private val lastPhotoFile = File(cacheDir, "last_photo.jpg")
    private val lastPhotoUrlFile = File(cacheDir, "last_photo_url.txt")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "PhotoCache"
    }

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    suspend fun cacheLastPhotoUrl(url: String) = withContext(Dispatchers.IO) {
        try {
            lastPhotoUrlFile.writeText(url)
            Log.d(TAG, "Cached photo URL: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching photo URL", e)
        }
    }

    fun getLastCachedPhotoUrl(): String? {
        return try {
            if (lastPhotoUrlFile.exists()) {
                lastPhotoUrlFile.readText()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached photo URL", e)
            null
        }
    }

    suspend fun cacheLastPhotoBitmap(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(lastPhotoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "Cached photo bitmap successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error caching photo bitmap", e)
        }
    }

    suspend fun getLastCachedPhotoBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        if (!lastPhotoFile.exists()) return@withContext null

        try {
            BitmapFactory.decodeFile(lastPhotoFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached photo bitmap", e)
            null
        }
    }

    fun cleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                if (lastPhotoFile.exists()) {
                    lastPhotoFile.delete()
                }
                if (lastPhotoUrlFile.exists()) {
                    lastPhotoUrlFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up cache", e)
            }
        }
    }
}