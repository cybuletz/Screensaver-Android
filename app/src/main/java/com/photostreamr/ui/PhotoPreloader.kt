package com.photostreamr.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.photostreamr.PhotoRepository
import com.photostreamr.glide.GlideApp
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PhotoPreloader @Inject constructor(
    private val context: Context,
    private val photoManager: PhotoRepository
) {
    companion object {
        private const val TAG = "PhotoPreloader"
        private const val DEFAULT_PRELOAD_COUNT = 3 // Number of photos to preload ahead
        private const val MAX_CONCURRENT_PRELOADS = 2 // Maximum concurrent preload operations
    }

    private val preloaderJob = SupervisorJob()
    private val preloaderScope = CoroutineScope(Dispatchers.IO + preloaderJob)

    // Queue of photo URLs to preload
    private val preloadQueue = ConcurrentLinkedQueue<String>()

    // Keep track of preloaded resources
    private val preloadedResources = mutableMapOf<String, Drawable>()

    // Track active preload operations
    private var activePreloads = 0

    // Flag to indicate if preloader is running
    private var isPreloading = false

    // Random for simulating non-deterministic order
    private val random = Random.Default

    // Preloading configuration
    private var preloadCount = DEFAULT_PRELOAD_COUNT
    private var isRandomOrder = false

    /**
     * Start preloading process for upcoming photos
     */
    fun startPreloading(currentPhotoIndex: Int, isRandomOrder: Boolean = false) {
        this.isRandomOrder = isRandomOrder

        if (isPreloading) {
            return // Already preloading
        }

        isPreloading = true

        preloaderScope.launch {
            try {
                preloadNextPhotos(currentPhotoIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting photo preloading", e)
            }
        }
    }

    /**
     * Stop preloading process
     */
    fun stopPreloading() {
        isPreloading = false
        preloadQueue.clear()
        preloadedResources.clear()
    }

    /**
     * Set the number of photos to preload ahead
     */
    fun setPreloadCount(count: Int) {
        preloadCount = count.coerceIn(1, 5) // Reasonable limits
    }

    /**
     * Check if a photo is already preloaded
     */
    fun isPhotoPreloaded(url: String): Boolean {
        return preloadedResources.containsKey(url)
    }

    /**
     * Get a preloaded resource if available
     */
    fun getPreloadedResource(url: String): Drawable? {
        return preloadedResources[url]
    }

    /**
     * Remove a preloaded resource (call when it's been used)
     */
    fun removePreloadedResource(url: String) {
        preloadedResources.remove(url)
    }

    /**
     * Preload the next set of photos based on current index
     */
    private suspend fun preloadNextPhotos(currentPhotoIndex: Int) {
        withContext(Dispatchers.IO) {
            try {
                val photoCount = photoManager.getPhotoCount()
                if (photoCount <= 1) {
                    return@withContext // Nothing to preload
                }

                // Clear existing queue
                preloadQueue.clear()

                // Determine next indices to preload
                val indicesToPreload = if (isRandomOrder) {
                    // For random order, select random indices different from current
                    (0 until preloadCount).map {
                        var nextIndex: Int
                        do {
                            nextIndex = random.nextInt(photoCount)
                        } while (nextIndex == currentPhotoIndex)
                        nextIndex
                    }
                } else {
                    // For sequential order, take next N indices
                    (1..preloadCount).map { offset ->
                        (currentPhotoIndex + offset) % photoCount
                    }
                }

                // Add URLs to preload queue
                for (index in indicesToPreload) {
                    val url = photoManager.getPhotoUrl(index) ?: continue

                    // Check if we need to get a cached version (for Google Photos)
                    val isGooglePhotosUri = url.contains("com.google.android.apps.photos") ||
                            url.contains("googleusercontent.com")

                    val urlToAdd = if (isGooglePhotosUri) {
                        val cachedUri = photoManager.persistentPhotoCache?.getCachedPhotoUri(url)
                        cachedUri ?: continue // Skip if not cached
                    } else {
                        url
                    }

                    if (!preloadedResources.containsKey(urlToAdd)) {
                        preloadQueue.add(urlToAdd)
                    }
                }

                // Process the queue
                processPreloadQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading next photos", e)
            }
        }
    }

    /**
     * Process the preload queue, respecting concurrency limits
     */
    private suspend fun processPreloadQueue() {
        withContext(Dispatchers.Main) {
            while (isPreloading && preloadQueue.isNotEmpty() && activePreloads < MAX_CONCURRENT_PRELOADS) {
                val url = preloadQueue.poll() ?: break
                preloadPhoto(url)
            }
        }
    }

    /**
     * Preload a single photo using Glide
     */
    private fun preloadPhoto(url: String) {
        if (!isPreloading || url in preloadedResources) {
            return
        }

        activePreloads++

        GlideApp.with(context)
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e(TAG, "Failed to preload: $url", e)
                    activePreloads--

                    preloaderScope.launch {
                        processPreloadQueue()
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.d(TAG, "Successfully preloaded: $url")
                    preloadedResources[url] = resource
                    activePreloads--

                    preloaderScope.launch {
                        processPreloadQueue()
                    }
                    return false
                }
            })
            .preload()
    }

    /**
     * Update based on a new current photo index
     */
    fun updateCurrentIndex(newIndex: Int) {
        if (!isPreloading) {
            return
        }

        preloaderScope.launch {
            preloadNextPhotos(newIndex)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopPreloading()
        preloaderJob.cancel()
    }
}