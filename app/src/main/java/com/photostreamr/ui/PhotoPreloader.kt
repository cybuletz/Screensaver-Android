package com.photostreamr.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import com.photostreamr.PhotoRepository
import com.photostreamr.photos.CoilImageLoadStrategy
import com.photostreamr.photos.ImageLoadStrategy
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PhotoPreloader @Inject constructor(
    private val context: Context,
    private val photoManager: PhotoRepository,
    private val bitmapMemoryManager: BitmapMemoryManager,
    private val imageLoadStrategy: CoilImageLoadStrategy
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

    // Track content:// URIs we've opened
    private val openContentUris = ConcurrentHashMap<String, Long>()

    /**
     * Release resources for a content:// URI to prevent native heap accumulation
     * This is particularly important for Android 8.0 (Oreo)
     */
    fun releaseContentUri(uri: String) {
        if (!uri.startsWith("content://")) return

        // Move the actual I/O operation to background thread
        preloaderScope.launch(Dispatchers.IO) {
            try {
                // Remove from tracking
                openContentUris.remove(uri)

                // Try to explicitly close resources (now on IO thread)
                context.contentResolver.openInputStream(Uri.parse(uri))?.close()

                // Force GC on Android 8 to help release native resources
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                    System.gc()
                }

                Log.d(TAG, "Released resources for content URI: $uri")
            } catch (e: Exception) {
                Log.d(TAG, "Non-critical error releasing content URI: ${e.message}")
            }
        }
    }

    /**
     * Pre-prepare a content:// URI for loading to ensure proper resource management
     */
    suspend fun prepareContentUri(uri: String) = withContext(Dispatchers.IO) {
        if (!uri.startsWith("content://")) return@withContext

        try {
            // Track when we accessed this URI
            openContentUris[uri] = System.currentTimeMillis()

            // Pre-open and close to ensure it's accessible
            context.contentResolver.openInputStream(Uri.parse(uri))?.close()

            Log.d(TAG, "Prepared content URI: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing content URI: $uri", e)
        }
    }

    /**
     * Clean up any content URIs we've tracked
     */
    private fun cleanupContentUris() {
        // Only keep track of the last 10 URIs to avoid memory bloat
        if (openContentUris.size > 10) {
            // Get oldest URIs
            val oldestEntries = openContentUris.entries
                .sortedBy { it.value }
                .take(openContentUris.size - 10)

            // Release them
            oldestEntries.forEach { (uri, _) ->
                try {
                    releaseContentUri(uri)
                } catch (e: Exception) {
                    // Ignore, best effort cleanup
                }
            }

            Log.d(TAG, "Cleaned up ${oldestEntries.size} old content URIs")
        }
    }

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
     * Stop preloading process and unregister remaining bitmaps.
     */
    fun stopPreloading() {
        isPreloading = false
        preloadQueue.clear()

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

    /**
     * Remove a preloaded resource (call when it's been used) and unregister from BitmapMemoryManager.
     */
    fun removePreloadedResource(url: String) {
        val removedDrawable = preloadedResources.remove(url) // Remove from internal map first

        // If successfully removed from internal map AND it was a BitmapDrawable, unregister from BitmapMemoryManager
        if (removedDrawable != null && removedDrawable is BitmapDrawable) {
            val preloadKey = "preload:$url"
            Log.d(TAG, "Unregistering used preloaded bitmap with key: $preloadKey")
            bitmapMemoryManager.unregisterActiveBitmap(preloadKey)
        } else if (removedDrawable != null) {
            Log.d(TAG, "Removed preloaded resource for $url, but it wasn't a BitmapDrawable.")
        }

        // Periodically clean up any recycled bitmaps and content URIs
        if (random.nextInt(10) == 0) {  // 10% chance to clean up
            cleanupRecycledResources()
            cleanupContentUris()
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
                // Also unregister from BitmapMemoryManager
                val preloadKey = "preload:$url"
                Log.w(TAG, "Unregistering recycled bitmap found during cleanup: $preloadKey")
                bitmapMemoryManager.unregisterActiveBitmap(preloadKey)
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
    suspend fun processPreloadQueue() {
        // Run processing on the Main thread to start Coil loads, but processing happens elsewhere
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
     * Preload a single photo using Coil
     */
    private fun preloadPhoto(url: String) {
        if (!isPreloading) {
            return
        }

        activePreloads++
        Log.d(TAG, "Starting preload for: $url (Active: $activePreloads)")

        preloaderScope.launch {
            try {
                // For content:// URIs, do special preparation
                if (url.startsWith("content://")) {
                    prepareContentUri(url)
                }

                val options = ImageLoadStrategy.ImageLoadOptions(
                    diskCacheStrategy = ImageLoadStrategy.DiskCacheStrategy.ALL,
                    isHighPriority = false
                )

                imageLoadStrategy.preloadImage(url, options)
                    .onSuccess { drawable ->
                        Log.d(TAG, "Successfully preloaded: $url")

                        // Register this bitmap with the memory manager for tracking
                        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                            val preloadKey = "preload:$url"
                            bitmapMemoryManager.registerActiveBitmap(preloadKey, drawable.bitmap)
                        }

                        // Store the resource locally
                        preloadedResources[url] = drawable
                        activePreloads--

                        // Continue processing the queue
                        preloaderScope.launch { processPreloadQueue() }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Failed to preload: $url", error)
                        activePreloads--
                        // Try processing queue again on failure
                        preloaderScope.launch { processPreloadQueue() }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error in preload", e)
                activePreloads--
                preloaderScope.launch { processPreloadQueue() }
            }
        }
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

        // Also clean up any content URIs we've accessed
        openContentUris.keys.forEach { uri ->
            try {
                releaseContentUri(uri)
            } catch (e: Exception) {
                // Ignore, just cleaning up
            }
        }
        openContentUris.clear()

        preloaderJob.cancel()
    }
}