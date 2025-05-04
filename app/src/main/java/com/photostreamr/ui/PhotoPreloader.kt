package com.photostreamr.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
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
    private val photoManager: PhotoRepository,
    private val bitmapMemoryManager: BitmapMemoryManager // Keep this injection
) {
    companion object {
        private const val TAG = "PhotoPreloader"
        private const val DEFAULT_PRELOAD_COUNT = 3
        private const val MAX_CONCURRENT_PRELOADS = 2
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

    // --- Method Modified ---
    /**
     * Stop preloading process and unregister remaining bitmaps.
     */
    fun stopPreloading() {
        isPreloading = false
        preloadQueue.clear()

        // --- START FIX ---
        // Unregister all remaining tracked preloaded bitmaps from BitmapMemoryManager
        preloadedResources.keys.forEach { url ->
            val preloadKey = "preload:$url"
            // Check if the drawable is a bitmap before unregistering
            val drawable = preloadedResources[url]
            if (drawable is BitmapDrawable) {
                Log.d(TAG, "Unregistering remaining preloaded bitmap on stop: $preloadKey")
                bitmapMemoryManager.unregisterActiveBitmap(preloadKey)
            }
        }
        // --- END FIX ---

        preloadedResources.clear() // Clear internal map
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
     * Checks if bitmap is recycled before returning
     */
    fun getPreloadedResource(url: String): Drawable? {
        val resource = preloadedResources[url] ?: return null

        // Check if it's a BitmapDrawable and if the bitmap is recycled
        if (resource is BitmapDrawable && resource.bitmap.isRecycled) {
            // Remove the recycled resource from our cache
            Log.w(TAG, "Found recycled bitmap in preload cache for $url, removing.")
            removePreloadedResource(url) // Call remove to ensure unregistration
            return null
        }

        return resource
    }

    // --- Method Modified ---
    /**
     * Remove a preloaded resource (call when it's been used) and unregister from BitmapMemoryManager.
     */
    fun removePreloadedResource(url: String) {
        val removedDrawable = preloadedResources.remove(url) // Remove from internal map first

        // --- START FIX ---
        // If successfully removed from internal map AND it was a BitmapDrawable, unregister from BitmapMemoryManager
        if (removedDrawable != null && removedDrawable is BitmapDrawable) {
            val preloadKey = "preload:$url"
            Log.d(TAG, "Unregistering used preloaded bitmap with key: $preloadKey")
            bitmapMemoryManager.unregisterActiveBitmap(preloadKey)
        } else if (removedDrawable != null) {
            Log.d(TAG, "Removed preloaded resource for $url, but it wasn't a BitmapDrawable.")
        }
        // --- END FIX ---

        // Periodically clean up any recycled bitmaps (original logic)
        if (random.nextInt(10) == 0) {  // 10% chance to clean up
            cleanupRecycledResources()
        }
    }


    /**
     * Clean up any recycled bitmaps in our cache
     */
    private fun cleanupRecycledResources() {
        val iterator = preloadedResources.entries.iterator()
        var count = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val drawable = entry.value
            if (drawable is BitmapDrawable && drawable.bitmap.isRecycled) {
                val url = entry.key
                iterator.remove() // Remove from internal map
                // --- START FIX ---
                // Also unregister from BitmapMemoryManager
                val preloadKey = "preload:$url"
                Log.w(TAG, "Unregistering recycled bitmap found during cleanup: $preloadKey")
                bitmapMemoryManager.unregisterActiveBitmap(preloadKey)
                // --- END FIX ---
                count++
            }
        }

        if (count > 0) {
            Log.d(TAG, "Removed and unregistered $count recycled bitmaps from preload cache during cleanup")
        }
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
                    }.distinct() // Ensure unique indices even in random selection
                } else {
                    // For sequential order, take next N indices
                    (1..preloadCount).map { offset ->
                        (currentPhotoIndex + offset) % photoCount
                    }
                }.distinct() // Ensure unique indices

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

                    // Only add if not already preloaded AND not currently in the queue
                    if (!preloadedResources.containsKey(urlToAdd) && !preloadQueue.contains(urlToAdd)) {
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
        // Run processing on the Main thread to start Glide loads, but listener runs elsewhere
        withContext(Dispatchers.Main) {
            while (isPreloading && preloadQueue.isNotEmpty() && activePreloads < MAX_CONCURRENT_PRELOADS) {
                val url = preloadQueue.poll() ?: break
                // Check again if it got preloaded while waiting in the queue
                if (!preloadedResources.containsKey(url)) {
                    preloadPhoto(url)
                }
            }
        }
    }

    /**
     * Preload a single photo using Glide
     */
    private fun preloadPhoto(url: String) {
        if (!isPreloading) { // Check if preloading is still active
            return
        }

        activePreloads++
        Log.d(TAG, "Starting preload for: $url (Active: $activePreloads)")

        GlideApp.with(context.applicationContext) // Use application context
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache aggressively
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e(TAG, "Failed to preload: $url", e)
                    activePreloads--
                    // Try processing queue again on failure
                    preloaderScope.launch { processPreloadQueue() }
                    return false // Let Glide handle error placeholder if needed elsewhere
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.d(TAG, "Successfully preloaded: $url")

                    // --- START FIX ---
                    // Register this bitmap with the memory manager for tracking
                    // Use a specific key format "preload:<url>"
                    if (resource is BitmapDrawable && !resource.bitmap.isRecycled) {
                        val preloadKey = "preload:$url"
                        bitmapMemoryManager.registerActiveBitmap(preloadKey, resource.bitmap)
                    } else {
                        Log.w(TAG, "Preloaded resource is not a non-recycled BitmapDrawable for $url, cannot track.")
                    }
                    // --- END FIX ---

                    // Store the resource locally
                    preloadedResources[url] = resource
                    activePreloads--

                    // Continue processing the queue
                    preloaderScope.launch { processPreloadQueue() }

                    // Return false so Glide doesn't try to display it anywhere else
                    return false
                }
            })
            .preload() // Use preload() which doesn't require a target view
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
        stopPreloading() // This now handles unregistration
        preloaderJob.cancel()
    }
}