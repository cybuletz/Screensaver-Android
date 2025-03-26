package com.photostreamr.utils

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.models.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

@Singleton
class PhotoLoadingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {
    private val requestManager = Glide.with(context)

    private lateinit var diskCache: File


    companion object {
        const val QUALITY_LOW = 1
        const val QUALITY_MEDIUM = 2
        const val QUALITY_HIGH = 3
        private const val TAG = "PhotoLoadingManager"
    }

    init {
        // Move disk operations to a background thread
        scope.launch(Dispatchers.IO) {
            diskCache = File(context.cacheDir, "photo_cache").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    fun preloadPhoto(mediaItem: MediaItem) {
        try {
            requestManager
                .load(mediaItem.baseUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .preload()
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading photo: ${mediaItem.id}", e)
        }
    }

    fun isPhotoCached(mediaItem: MediaItem): Boolean {
        return try {
            Glide.with(context)
                .load(mediaItem.baseUrl)
                .onlyRetrieveFromCache(true)
                .submit()
                .get() != null
        } catch (e: Exception) {
            false
        }
    }

    fun cleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                Glide.get(context).clearDiskCache()
                withContext(Dispatchers.Main) {
                    Glide.get(context).clearMemory()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}