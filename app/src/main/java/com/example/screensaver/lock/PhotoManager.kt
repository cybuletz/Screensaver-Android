// File: app/src/main/java/com/example/screensaver/lock/PhotoManager.kt

package com.example.screensaver.lock

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoManager private constructor(private val context: Context) {
    private val sharedPhotoManager = com.example.screensaver.utils.PhotoManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "LockScreenPhotoManager"
        @Volatile private var instance: PhotoManager? = null

        fun getInstance(context: Context): PhotoManager {
            return instance ?: synchronized(this) {
                instance ?: PhotoManager(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun loadPhotos(): Boolean = withContext(Dispatchers.IO) {
        try {
            sharedPhotoManager.loadGooglePhotos()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos: ${e.message}")
            false
        }
    }

    fun getPhotoCount(): Int = sharedPhotoManager.getPhotoCount()

    fun getPhotoUrl(index: Int): String? = sharedPhotoManager.getPhotoUrl(index)

    suspend fun preloadNextPhoto(index: Int) {
        sharedPhotoManager.preloadNextPhoto(index)
    }
}