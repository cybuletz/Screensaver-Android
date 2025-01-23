package com.example.screensaver.loading

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.collection.LruCache
import coil.ImageLoader
import coil.load
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import com.example.screensaver.models.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Manages photo loading, caching, and memory management for the application.
 * Handles efficient loading of photos and maintains a cache to improve performance.
 */
class PhotoLoadingManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val imageLoader: ImageLoader
    private val diskCache: File
    private val loadingJobs = mutableMapOf<String, Job>()
    private var currentLoadingItem: MediaItem? = null

    companion object {
        private const val CACHE_SIZE_PERCENTAGE = 0.25 // Use 25% of available memory
        private const val DISK_CACHE_SIZE = 250L * 1024 * 1024 // 250MB
        private const val CORNER_RADIUS = 8f
        private const val CROSSFADE_DURATION = 300
    }

    init {
        // Initialize disk cache directory
        diskCache = File(context.cacheDir, "photo_cache").apply {
            if (!exists()) mkdirs()
        }

        // Calculate memory cache size (25% of available memory)
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSize = (maxMemory * CACHE_SIZE_PERCENTAGE).toInt()

        // Configure and build image loader
        imageLoader = ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(CACHE_SIZE_PERCENTAGE)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(diskCache)
                    .maxSizeBytes(DISK_CACHE_SIZE)
                    .build()
            }
            .crossfade(CROSSFADE_DURATION)
            .build()
    }

    /**
     * Loads a photo into the specified ImageView
     */
    fun loadPhoto(
        mediaItem: MediaItem,
        imageView: ImageView,
        preload: Boolean = false,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        // Cancel any existing loading job for this view
        cancelLoadingForView(imageView)

        val job = scope.launch {
            try {
                currentLoadingItem = mediaItem

                val request = ImageRequest.Builder(context)
                    .data(mediaItem.baseUrl)
                    .target(
                        onStart = {
                            if (!preload) {
                                mediaItem.updateLoadState(MediaItem.LoadState.LOADING)
                            }
                        },
                        onSuccess = { drawable ->
                            if (!preload) {
                                imageView.setImageDrawable(drawable)
                                mediaItem.updateLoadState(MediaItem.LoadState.LOADED)
                                onSuccess()
                            }
                        },
                        onError = {
                            if (!preload) {
                                mediaItem.updateLoadState(MediaItem.LoadState.ERROR)
                                onError()
                            }
                        }
                    )
                    .scale(Scale.FIT)
                    .transformations(RoundedCornersTransformation(CORNER_RADIUS))
                    .build()

                withContext(Dispatchers.IO) {
                    imageLoader.execute(request)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    mediaItem.updateLoadState(MediaItem.LoadState.ERROR)
                    onError()
                }
            } finally {
                if (currentLoadingItem == mediaItem) {
                    currentLoadingItem = null
                }
                loadingJobs.remove(mediaItem.id)
            }
        }

        loadingJobs[mediaItem.id] = job
    }

    /**
     * Preloads a photo into memory cache
     */
    fun preloadPhoto(mediaItem: MediaItem) {
        loadPhoto(mediaItem, ImageView(context), preload = true)
    }

    /**
     * Cancels loading for a specific view
     */
    private fun cancelLoadingForView(imageView: ImageView) {
        imageLoader.dispose(imageView)
    }

    /**
     * Cancels loading for a specific media item
     */
    fun cancelLoading(mediaItem: MediaItem) {
        loadingJobs[mediaItem.id]?.cancel()
        loadingJobs.remove(mediaItem.id)
        if (currentLoadingItem == mediaItem) {
            currentLoadingItem = null
        }
    }

    /**
     * Cancels all ongoing loading operations
     */
    fun cancelAllLoading() {
        loadingJobs.values.forEach { it.cancel() }
        loadingJobs.clear()
        currentLoadingItem = null
    }

    /**
     * Clears memory cache
     */
    fun clearMemoryCache() {
        imageLoader.memoryCache?.clear()
    }

    /**
     * Clears disk cache
     */
    suspend fun clearDiskCache() {
        withContext(Dispatchers.IO) {
            imageLoader.diskCache?.clear()
        }
    }

    /**
     * Returns true if the photo is cached
     */
    fun isPhotoCached(mediaItem: MediaItem): Boolean {
        val memoryKey = MemoryCache.Key(mediaItem.baseUrl)
        return imageLoader.memoryCache?.get(memoryKey) != null ||
                imageLoader.diskCache?.get(mediaItem.baseUrl) != null
    }

    /**
     * Gets the size of cached photos
     */
    fun getCacheSize(): Long {
        val memoryCacheSize = imageLoader.memoryCache?.size ?: 0L
        val diskCacheSize = imageLoader.diskCache?.size ?: 0L
        return memoryCacheSize + diskCacheSize
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        cancelAllLoading()
        clearMemoryCache()
        scope.launch {
            clearDiskCache()
        }
    }

    /**
     * Configuration class for PhotoLoadingManager
     */
    data class Config(
        val maxMemoryCacheSize: Int = (Runtime.getRuntime()
            .maxMemory() * CACHE_SIZE_PERCENTAGE).toInt(),
        val maxDiskCacheSize: Long = DISK_CACHE_SIZE,
        val cornerRadius: Float = CORNER_RADIUS,
        val crossfadeDuration: Int = CROSSFADE_DURATION
    )
}