package com.photostreamr.utils

import android.content.Context
import com.photostreamr.models.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import coil.ImageLoader
import coil.request.CachePolicy

@Singleton
class PhotoLoadingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {
    private val imageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .diskCachePolicy(CachePolicy.ENABLED)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .build()

    private lateinit var diskCache: File

    companion object {
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

    fun cleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                // Clear disk cache
                imageLoader.diskCache?.clear()

                withContext(Dispatchers.Main) {
                    // Clear memory cache
                    imageLoader.memoryCache?.clear()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}