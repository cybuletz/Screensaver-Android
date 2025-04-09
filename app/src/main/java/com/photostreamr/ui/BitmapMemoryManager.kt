package com.photostreamr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Manages memory for photo bitmaps with automatic cleanup cycles
 * to prevent out of memory errors during slideshow
 */
@Singleton
class BitmapMemoryManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "BitmapMemoryManager"

        // Memory thresholds
        private const val MEMORY_PRESSURE_THRESHOLD_PERCENT = 70.0f
        private const val SEVERE_MEMORY_PRESSURE_THRESHOLD_PERCENT = 85.0f

        // Cleanup cycles configuration
        private const val MIN_PHOTOS_BETWEEN_CLEANUPS = 8
        private const val DEFAULT_CLEANUP_CYCLE_LENGTH = 3
        private const val SEVERE_CLEANUP_CYCLE_LENGTH = 5

        // Random single photo frequency for visual variety (1 in X chance)
        private const val VARIETY_SINGLE_PHOTO_CHANCE = 5
    }

    // Track active cleanup state
    private var isInCleanupCycle = false
    private var cleanupPhotosRemaining = 0
    private var photosSinceLastCleanup = 0

    // Track active bitmaps (using WeakReferences to avoid memory leaks)
    private val activeBitmaps = ConcurrentHashMap<String, WeakReference<Bitmap>>()

    // Coroutine management
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(Dispatchers.IO + managerJob)

    // Track memory state
    private var memoryPressureLevel = MemoryPressureLevel.NORMAL

    enum class MemoryPressureLevel {
        NORMAL,
        ELEVATED,
        SEVERE
    }

    init {
        startMemoryMonitoring()
    }

    /**
     * Determine if we should display a single photo instead of a template
     * - For memory cleanup when needed
     * - For visual variety randomly
     */
    fun shouldShowSinglePhoto(): Boolean {
        // Check if we're in an active cleanup cycle
        if (isInCleanupCycle) {
            cleanupPhotosRemaining--
            Log.d(TAG, "In cleanup cycle, $cleanupPhotosRemaining photos remaining")

            if (cleanupPhotosRemaining <= 0) {
                isInCleanupCycle = false
                Log.d(TAG, "Cleanup cycle completed")
            }

            return true
        }

        // Check if we need to start a cleanup cycle based on memory pressure
        if (memoryPressureLevel != MemoryPressureLevel.NORMAL &&
            photosSinceLastCleanup >= MIN_PHOTOS_BETWEEN_CLEANUPS) {

            startCleanupCycle()
            return true
        }

        // Show single photos occasionally for visual variety
        if (Random.nextInt(VARIETY_SINGLE_PHOTO_CHANCE) == 0) {
            Log.d(TAG, "Showing single photo for visual variety")
            return true
        }

        photosSinceLastCleanup++
        return false
    }

    /**
     * Start a cleanup cycle - a sequence of single photos to help release memory
     */
    private fun startCleanupCycle() {
        isInCleanupCycle = true
        photosSinceLastCleanup = 0

        // Determine cleanup cycle length based on memory pressure
        cleanupPhotosRemaining = if (memoryPressureLevel == MemoryPressureLevel.SEVERE) {
            SEVERE_CLEANUP_CYCLE_LENGTH
        } else {
            DEFAULT_CLEANUP_CYCLE_LENGTH
        }

        Log.d(TAG, "Starting cleanup cycle with $cleanupPhotosRemaining photos, memory pressure: $memoryPressureLevel")

        // Clear memory caches
        clearMemoryCaches()
    }

    /**
     * Register a bitmap that's currently being displayed
     * IMPORTANT: Only tracks the bitmap with a WeakReference, doesn't take ownership
     */
    fun registerActiveBitmap(key: String, bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Attempted to register a recycled bitmap")
            return
        }

        activeBitmaps[key] = WeakReference(bitmap)
        Log.d(TAG, "Registered active bitmap: $key, current active count: ${activeBitmaps.size}")
    }

    /**
     * Unregister a bitmap that's no longer being displayed
     * IMPORTANT: Does NOT recycle the bitmap, only removes our reference to it
     */
    fun unregisterActiveBitmap(key: String) {
        activeBitmaps.remove(key)?.get()?.let {
            Log.d(TAG, "Unregistered active bitmap: $key")
        }
    }

    /**
     * Clear memory caches without directly recycling bitmaps
     */
    fun clearMemoryCaches() {
        managerScope.launch {
            try {
                // Clean up our tracking map
                cleanupTrackingMap()

                // Clear Glide's memory cache on main thread
                withContext(Dispatchers.Main) {
                    try {
                        Log.d(TAG, "Clearing Glide memory cache")
                        Glide.get(context).clearMemory()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing Glide memory cache", e)
                    }
                }

                // Request garbage collection
                Log.d(TAG, "Requesting garbage collection")
                System.gc()

            } catch (e: Exception) {
                Log.e(TAG, "Error in clearMemoryCaches", e)
            }
        }
    }

    /**
     * Periodically monitor memory usage to adjust our strategy
     */
    private fun startMemoryMonitoring() {
        managerScope.launch {
            while (isActive) {
                try {
                    val memoryInfo = getMemoryInfo()
                    val usedPercent = memoryInfo.usedPercent

                    // Update memory pressure level
                    val previousLevel = memoryPressureLevel
                    memoryPressureLevel = when {
                        usedPercent >= SEVERE_MEMORY_PRESSURE_THRESHOLD_PERCENT -> {
                            if (previousLevel != MemoryPressureLevel.SEVERE) {
                                Log.w(TAG, "Severe memory pressure detected: $usedPercent%")
                                clearMemoryCaches()
                            }
                            MemoryPressureLevel.SEVERE
                        }
                        usedPercent >= MEMORY_PRESSURE_THRESHOLD_PERCENT -> {
                            if (previousLevel != MemoryPressureLevel.ELEVATED) {
                                Log.w(TAG, "Elevated memory pressure detected: $usedPercent%")
                            }
                            MemoryPressureLevel.ELEVATED
                        }
                        else -> MemoryPressureLevel.NORMAL
                    }

                    // Periodically clean tracking map
                    cleanupTrackingMap()

                    // Log active bitmap count periodically
                    Log.d(TAG, "Memory: ${activeBitmaps.size} tracked bitmaps, usage: $usedPercent%, level: $memoryPressureLevel")

                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error in memory monitoring", e)
                    }
                }

                delay(10000) // Check every 10 seconds
            }
        }
    }

    /**
     * Remove any NULL or recycled bitmap references from our tracking
     */
    private fun cleanupTrackingMap() {
        val iterator = activeBitmaps.entries.iterator()
        var removedCount = 0

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val bitmap = entry.value.get()

            if (bitmap == null || bitmap.isRecycled) {
                iterator.remove()
                removedCount++
            }
        }

        if (removedCount > 0) {
            Log.d(TAG, "Removed $removedCount stale entries from bitmap tracking")
        }
    }

    /**
     * Get current memory info
     */
    private fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()

        val usedPercent = (usedMemory.toFloat() / maxMemory.toFloat()) * 100

        return MemoryInfo(
            maxMemory = maxMemory,
            usedMemory = usedMemory,
            usedPercent = usedPercent
        )
    }

    data class MemoryInfo(
        val maxMemory: Long,
        val usedMemory: Long,
        val usedPercent: Float
    )

    fun cleanup() {
        managerJob.cancel()
    }
}