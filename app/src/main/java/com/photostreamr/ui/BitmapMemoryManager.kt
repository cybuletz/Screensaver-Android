package com.photostreamr.ui

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Debug
import android.util.Log
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Manages memory for photo bitmaps with automatic cleanup cycles
 * to prevent out of memory errors during slideshow
 */
@Singleton
class BitmapMemoryManager @Inject constructor(
    private val context: Context,
    private val diskCacheManager: DiskCacheManager
) {
    companion object {
        private const val TAG = "BitmapMemoryManager"

        // Memory thresholds
        private const val MEMORY_PRESSURE_THRESHOLD_PERCENT = 50.0f
        private const val SEVERE_MEMORY_PRESSURE_THRESHOLD_PERCENT = 60.0f

        // Cleanup cycles configuration
        private const val MIN_PHOTOS_BETWEEN_CLEANUPS = 10
        private const val DEFAULT_CLEANUP_CYCLE_LENGTH = 3
        private const val SEVERE_CLEANUP_CYCLE_LENGTH = 5

        // Random single photo frequency for visual variety (1 in X chance)
        private const val VARIETY_SINGLE_PHOTO_CHANCE = 5

        // Memory logging interval
        private const val LOG_MEMORY_INTERVAL_MS = 30000L // 30 seconds
    }

    // Track active cleanup state
    private var isInCleanupCycle = false
    private var cleanupPhotosRemaining = 0
    private var photosSinceLastCleanup = 0

    // Track active bitmaps (using WeakReferences to avoid memory leaks)
    private val activeBitmaps = ConcurrentHashMap<String, WeakReference<Bitmap>>()

    // Coroutine management
    private var managerJob = SupervisorJob()
    private var managerScope = CoroutineScope(Dispatchers.IO + managerJob)


    // Track memory state
    private var memoryPressureLevel = MemoryPressureLevel.NORMAL

    // Memory metrics tracking
    private val totalBitmapMemory = AtomicLong(0)
    private var lastCleanupTime: Long = 0
    private var memoryBeforeLastCleanup: Long = 0
    private var memoryAfterLastCleanup: Long = 0
    private val decimalFormat = DecimalFormat("#.##")
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Memory history for tracking trends
    private val memoryHistory = LinkedList<MemorySnapshot>()
    private val maxHistorySize = 10

    private var isMonitoringActive = false

    enum class MemoryPressureLevel {
        NORMAL,
        ELEVATED,
        SEVERE
    }

    data class MemorySnapshot(
        val timestamp: Long,
        val totalMemory: Long,
        val usedMemory: Long,
        val usedPercent: Float,
        val bitmapCount: Int,
        val estimatedBitmapMemory: Long
    ) {
        fun getFormattedTimestamp(): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestamp))
    }

    init {
        startMemoryMonitoring()
        startDetailedMemoryLogging()
    }

    /**
     * Resume memory monitoring if it was stopped
     * Call this when returning to the main screen
     */
    fun startMonitoring(forceCleanupNow: Boolean = false) {
        if (isMonitoringActive) {
            Log.d(TAG, "📊 Memory monitoring already active, no action needed")
            return
        }

        // Full restart needed
        Log.i(TAG, "📊 Starting memory monitoring with new coroutine scope")

        // CHANGE: Create fresh job and scope
        managerJob = SupervisorJob()
        managerScope = CoroutineScope(Dispatchers.IO + managerJob)

        isMonitoringActive = true
        startMemoryMonitoring()
        startDetailedMemoryLogging()

        // Only start scheduler if it's not already running
        if (schedulerJob == null || schedulerJob?.isActive != true) {
            Log.i(TAG, "📊 Starting scheduled cleanup as part of monitoring start")
            startScheduledCleanup()
        } else {
            Log.i(TAG, "📊 Scheduler already running, not restarting it")
        }

        // Only perform cleanup if explicitly requested
        if (forceCleanupNow) {
            Log.i(TAG, "📊 Forcing immediate memory cleanup on start (explicitly requested)")
            clearMemoryCaches()
        } else {
            Log.d(TAG, "📊 Starting monitoring without cleanup")
        }
    }

    private var schedulerJob: Job? = null

    fun ensureSchedulerRunning() {
        if (schedulerJob == null || schedulerJob?.isActive != true) {
            Log.i(TAG, "📊 Ensuring scheduler is running")
            startScheduledCleanup()
        }
    }

    private fun startScheduledCleanup() {
        // Cancel existing job if any
        schedulerJob?.cancel()

        // Create new job
        schedulerJob = managerScope.launch {
            // Delay initial cleanup
            delay(10_000)

            while (isActive) {
                try {
                    // Calculate cleanup interval
                    val cleanupIntervalMs = 5 * 60 * 1000L // 5 minutes default

                    Log.i(TAG, "💾 Scheduling next cache cleanup in ${cleanupIntervalMs/1000}s")
                    delay(cleanupIntervalMs)

                    // Check memory pressure and perform appropriate cleanup
                    val memoryInfo = getMemoryInfo()
                    if (memoryInfo.usedPercent > 60f) {
                        Log.i(TAG, "💾 Memory pressure detected (${memoryInfo.usedPercent.toInt()}%), triggering bitmap AND disk cache cleanup")
                        clearMemoryCaches()

                        // Add disk cleanup when memory pressure is high
                        diskCacheManager.cleanupDiskCache()
                    } else {
                        Log.i(TAG, "💾 Selective memory cleanup based on schedule")
                        clearMemoryCaches()

                        // Check disk cache size
                        val diskCacheSizeMB = diskCacheManager.getCurrentCacheSizeMB()
                        Log.i(TAG, "💾 Checking disk cache size: $diskCacheSizeMB MB")

                        if (diskCacheSizeMB > 5 && diskCacheManager.canPerformCleanup()) {
                            Log.i(TAG, "💾 Disk cache size ($diskCacheSizeMB MB) exceeds threshold, performing disk cleanup")
                            diskCacheManager.cleanupDiskCache()
                        }
                    }

                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Log.e(TAG, "Error in scheduled cleanup", e)
                    }
                }
            }
        }
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
                // Trigger memory logging after cleanup cycle completes
                logMemoryMetrics("🧹 Cleanup cycle completed")
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

        // Log memory state before cleanup
        lastCleanupTime = System.currentTimeMillis()
        val memoryInfo = getMemoryInfo()
        memoryBeforeLastCleanup = memoryInfo.usedMemory

        Log.d(TAG, "🚨 Starting cleanup cycle with $cleanupPhotosRemaining photos, memory pressure: $memoryPressureLevel")
        Log.d(TAG, "💾 Memory before cleanup: ${formatBytes(memoryBeforeLastCleanup)} / ${formatBytes(memoryInfo.maxMemory)} (${decimalFormat.format(memoryInfo.usedPercent)}%)")

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

        // Calculate bitmap memory size
        val byteCount = calculateBitmapSize(bitmap)
        totalBitmapMemory.addAndGet(byteCount)

        activeBitmaps[key] = WeakReference(bitmap)
        Log.d(TAG, "📊 Registered bitmap: $key (${formatBytes(byteCount)}), total tracked: ${activeBitmaps.size}")
    }

    /**
     * Unregister a bitmap that's no longer being displayed
     */
    fun unregisterActiveBitmap(key: String) {
        activeBitmaps.remove(key)?.get()?.let { bitmap ->
            if (!bitmap.isRecycled) {
                val byteCount = calculateBitmapSize(bitmap)
                totalBitmapMemory.addAndGet(-byteCount)
                Log.d(TAG, "🗑️ Unregistered bitmap: $key (${formatBytes(byteCount)})")
            }
        }
    }

    /**
     * Clear memory caches without directly recycling bitmaps
     */
    /**
     * Clear memory caches without directly recycling bitmaps
     */
    fun clearMemoryCaches() {
        managerScope.launch {
            try {
                // Log state before cleanup
                val beforeInfo = getMemoryInfo()
                val beforeCount = activeBitmaps.size
                val beforeBitmapMem = calculateTotalTrackedBitmapMemory()

                // Clean up our tracking map
                cleanupTrackingMap()

                // Clear Glide's memory cache on main thread
                withContext(Dispatchers.Main) {
                    try {
                        Log.d(TAG, "🧹 Clearing Glide memory cache")
                        Glide.get(context).clearMemory()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing Glide memory cache", e)
                    }
                }

                // Request garbage collection
                Log.d(TAG, "🗑️ Requesting garbage collection")
                System.gc()

                // Allow some time for GC to complete
                delay(200)

                // Log state after cleanup
                val afterInfo = getMemoryInfo()
                val afterCount = activeBitmaps.size
                val afterBitmapMem = calculateTotalTrackedBitmapMemory()

                // Record memory after cleanup
                memoryAfterLastCleanup = afterInfo.usedMemory

                // Calculate and log memory freed
                val memoryFreed = memoryBeforeLastCleanup - memoryAfterLastCleanup
                val removedBitmaps = beforeCount - afterCount
                val bitmapMemFreed = beforeBitmapMem - afterBitmapMem

                Log.d(TAG, """
            🧹 Memory cleanup complete:
            • Memory before: ${formatBytes(beforeInfo.usedMemory)} (${decimalFormat.format(beforeInfo.usedPercent)}%)
            • Memory after:  ${formatBytes(afterInfo.usedMemory)} (${decimalFormat.format(afterInfo.usedPercent)}%)
            • Memory freed:  ${formatBytes(memoryFreed.coerceAtLeast(0))}
            
            • Bitmaps before: $beforeCount (${formatBytes(beforeBitmapMem)})
            • Bitmaps after:  $afterCount (${formatBytes(afterBitmapMem)})
            • Bitmaps freed:  $removedBitmaps (${formatBytes(bitmapMemFreed.coerceAtLeast(0))})
            """.trimIndent())

            } catch (e: Exception) {
                Log.e(TAG, "Error in clearMemoryCaches", e)
            }
        }
    }

    /**
     * Start periodic detailed memory logging
     */
    private fun startDetailedMemoryLogging() {
        managerScope.launch {
            try {
                while (isActive) {
                    try {
                        logMemoryMetrics("📊 Periodic memory report")
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e(TAG, "Error logging memory metrics", e)
                        }
                    }
                    delay(LOG_MEMORY_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                // Expected during cancellation
                Log.d(TAG, "Memory logging cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in memory logging", e)
            } finally {
                isMonitoringActive = false
            }
        }
    }

    /**
     * Log detailed memory metrics
     */
    fun logMemoryMetrics(reason: String) {
        val memInfo = getMemoryInfo()
        val trackedBitmaps = activeBitmaps.size
        val trackedBitmapMem = calculateTotalTrackedBitmapMemory()
        val nativeHeap = Debug.getNativeHeapAllocatedSize()

        // Get ActivityManager memory info
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val systemAvailMem = memoryInfo.availMem
        val systemTotalMem = memoryInfo.totalMem
        val systemLowMemory = memoryInfo.lowMemory

        // Add to memory history
        val snapshot = MemorySnapshot(
            timestamp = System.currentTimeMillis(),
            totalMemory = memInfo.maxMemory,
            usedMemory = memInfo.usedMemory,
            usedPercent = memInfo.usedPercent,
            bitmapCount = trackedBitmaps,
            estimatedBitmapMemory = trackedBitmapMem
        )

        synchronized(memoryHistory) {
            memoryHistory.add(snapshot)
            if (memoryHistory.size > maxHistorySize) {
                memoryHistory.removeFirst()
            }
        }

        Log.i(TAG, """
            $reason
            📱 App Memory:
            • Java heap: ${formatBytes(memInfo.usedMemory)} / ${formatBytes(memInfo.maxMemory)} (${decimalFormat.format(memInfo.usedPercent)}%)
            • Native heap: ${formatBytes(nativeHeap)}
            • Memory pressure: $memoryPressureLevel
            
            🖼️ Bitmap Memory:
            • Tracked bitmaps: $trackedBitmaps
            • Estimated bitmap memory: ${formatBytes(trackedBitmapMem)}
            
            💻 System Memory:
            • Available: ${formatBytes(systemAvailMem)} / ${formatBytes(systemTotalMem)} (${decimalFormat.format(systemAvailMem.toFloat() / systemTotalMem * 100)}%)
            • Low memory state: $systemLowMemory
            
            ${getMemoryTrendReport()}
        """.trimIndent())
    }

    /**
     * Generate a memory trend report based on history
     */
    private fun getMemoryTrendReport(): String {
        if (memoryHistory.size < 2) return "📈 Memory trend: Insufficient data"

        val oldest = memoryHistory.first
        val newest = memoryHistory.last

        val memoryDiff = newest.usedMemory - oldest.usedMemory
        val percentDiff = newest.usedPercent - oldest.usedPercent
        val bitmapCountDiff = newest.bitmapCount - oldest.bitmapCount
        val bitmapMemDiff = newest.estimatedBitmapMemory - oldest.estimatedBitmapMemory

        val trend = when {
            percentDiff > 5 -> "🔴 INCREASING rapidly"
            percentDiff > 2 -> "🟠 INCREASING"
            percentDiff < -5 -> "🟢 DECREASING rapidly"
            percentDiff < -2 -> "🟢 DECREASING"
            else -> "🟡 STABLE"
        }

        return """
            📈 Memory trend ($trend):
            • Memory change: ${formatBytes(memoryDiff)} (${decimalFormat.format(percentDiff)}%)
            • Bitmap count change: $bitmapCountDiff
            • Bitmap memory change: ${formatBytes(bitmapMemDiff)}
            • Time period: ${formatTimeDifference(newest.timestamp - oldest.timestamp)}
        """.trimIndent()
    }

    /**
     * Format a time difference in ms to a human-readable string
     */
    private fun formatTimeDifference(diffMs: Long): String {
        val seconds = diffMs / 1000
        val minutes = seconds / 60

        return when {
            minutes > 0 -> "$minutes min ${seconds % 60} sec"
            else -> "$seconds sec"
        }
    }


    /**
     * Periodically monitor memory usage to adjust our strategy
     */
    private fun startMemoryMonitoring() {
        managerScope.launch {
            try {
                while (isActive) {
                    try {
                        val memoryInfo = getMemoryInfo()
                        val usedPercent = memoryInfo.usedPercent

                        // Update memory pressure level
                        val previousLevel = memoryPressureLevel
                        memoryPressureLevel = when {
                            usedPercent >= SEVERE_MEMORY_PRESSURE_THRESHOLD_PERCENT -> {
                                if (previousLevel != MemoryPressureLevel.SEVERE) {
                                    Log.w(TAG, "⚠️ SEVERE memory pressure detected: $usedPercent%")
                                    logMemoryMetrics("⚠️ SEVERE memory pressure detected")
                                    clearMemoryCaches()
                                }
                                MemoryPressureLevel.SEVERE
                            }
                            usedPercent >= MEMORY_PRESSURE_THRESHOLD_PERCENT -> {
                                if (previousLevel != MemoryPressureLevel.ELEVATED) {
                                    Log.w(TAG, "⚠️ ELEVATED memory pressure detected: $usedPercent%")
                                    logMemoryMetrics("⚠️ ELEVATED memory pressure detected")
                                }
                                MemoryPressureLevel.ELEVATED
                            }
                            activeBitmaps.size >= 12 -> { // Add this case to trigger on bitmap count
                                if (previousLevel != MemoryPressureLevel.ELEVATED) {
                                    Log.w(TAG, "⚠️ ELEVATED memory pressure due to bitmap count: ${activeBitmaps.size}")
                                    logMemoryMetrics("⚠️ ELEVATED memory pressure due to bitmap count")
                                }
                                MemoryPressureLevel.ELEVATED
                            }
                            else -> MemoryPressureLevel.NORMAL
                        }

                        // Periodically clean tracking map
                        cleanupTrackingMap()

                        // Log memory state every 30 seconds
                        Log.d(TAG, "📊 Memory: ${formatBytes(memoryInfo.usedMemory)} / ${formatBytes(memoryInfo.maxMemory)} (${decimalFormat.format(usedPercent)}%), ${activeBitmaps.size} tracked bitmaps, level: $memoryPressureLevel")

                    } catch (e: CancellationException) {
                        // Expected during cancellation, exit loop
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in memory monitoring", e)
                    }

                    delay(5000) // Check every 5 seconds
                }
            } catch (e: CancellationException) {
                // Expected during cancellation
                Log.d(TAG, "Memory monitoring cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in memory monitoring", e)
            } finally {
                isMonitoringActive = false
            }
        }
    }

    /**
     * Calculate total memory used by tracked bitmaps
     */
    private fun calculateTotalTrackedBitmapMemory(): Long {
        var total = 0L

        for (entry in activeBitmaps) {
            val bitmap = entry.value.get() ?: continue
            if (!bitmap.isRecycled) {
                total += calculateBitmapSize(bitmap)
            }
        }

        return total
    }

    /**
     * Calculate the memory size of a bitmap
     */
    private fun calculateBitmapSize(bitmap: Bitmap): Long {
        if (bitmap.isRecycled) return 0L

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                bitmap.allocationByteCount.toLong()
            } else {
                bitmap.byteCount.toLong()
            }
        } catch (e: Exception) {
            // Fallback calculation
            bitmap.width * bitmap.height * (if (bitmap.config == Bitmap.Config.ARGB_8888) 4 else 2).toLong()
        }
    }

    /**
     * Format bytes to human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val value = bytes.toDouble() / Math.pow(1024.0, digitGroups.toDouble())

        return "${decimalFormat.format(value)} ${units[digitGroups]}"
    }

    /**
     * Get current memory info
     */
    fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory

        val usedPercent = (usedMemory.toFloat() / maxMemory.toFloat()) * 100

        return MemoryInfo(
            maxMemory = maxMemory,
            totalMemory = totalMemory,
            freeMemory = freeMemory,
            usedMemory = usedMemory,
            usedPercent = usedPercent,
            trackedBitmaps = activeBitmaps.size
        )
    }


    /**
     * Clear all bitmap caches (memory and disk) for aggressive cleanup
     * This includes both memory caches and disk caches
     */
    fun clearAllBitmapCaches() {
        managerScope.launch {
            try {
                val memoryBefore = getMemoryInfo()
                val bitmapsBefore = activeBitmaps.size
                val bitmapMemBefore = calculateTotalTrackedBitmapMemory()

                Log.d(TAG, "🧹 Starting comprehensive bitmap cache clearing")

                // Clean up our tracking map
                val removedBitmaps = cleanupTrackingMap()

                // First clear memory cache on main thread
                withContext(Dispatchers.Main) {
                    try {
                        Log.d(TAG, "🧹 Clearing Glide memory cache")
                        Glide.get(context).clearMemory()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing Glide memory cache", e)
                    }
                }

                // Then clear disk cache on IO thread
                withContext(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "💾 Clearing Glide disk cache")
                        Glide.get(context).clearDiskCache()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing Glide disk cache", e)
                    }
                }

                // Request garbage collection
                Log.d(TAG, "🗑️ Requesting garbage collection")
                System.gc()

                // Give some time for GC to complete
                delay(300)

                // Log results
                val memoryAfter = getMemoryInfo()
                val bitmapsAfter = activeBitmaps.size
                val bitmapMemAfter = calculateTotalTrackedBitmapMemory()

                // Calculate freed memory
                val memoryFreed = memoryBefore.usedMemory - memoryAfter.usedMemory
                val bitmapsFreed = bitmapsBefore - bitmapsAfter
                val bitmapMemFreed = bitmapMemBefore - bitmapMemAfter

                Log.d(TAG, """
                🧹 Bitmap cache clearing completed:
                • Memory before: ${formatBytes(memoryBefore.usedMemory)} (${decimalFormat.format(memoryBefore.usedPercent)}%)
                • Memory after:  ${formatBytes(memoryAfter.usedMemory)} (${decimalFormat.format(memoryAfter.usedPercent)}%)
                • Memory freed:  ${formatBytes(memoryFreed.coerceAtLeast(0))}
                
                • Tracked bitmaps before: $bitmapsBefore (${formatBytes(bitmapMemBefore)})
                • Tracked bitmaps after:  $bitmapsAfter (${formatBytes(bitmapMemAfter)})
                • Removed from tracking:  $removedBitmaps
                • Bitmap memory freed:    ${formatBytes(bitmapMemFreed.coerceAtLeast(0))}
            """.trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "Error in clearAllBitmapCaches", e)
            }
        }
    }

    /**
     * On low memory condition, clear all caches including the bitmap pool
     */
    fun onLowMemory(smartPhotoLayoutManager: SmartPhotoLayoutManager) {
        managerScope.launch {
            try {
                Log.i(TAG, "🚨 Low memory condition detected!")

                // Log memory state before cleanup
                logMemoryMetrics("🚨 Before low memory cleanup")

                // Clear memory caches first
                clearMemoryCaches()

                // Then clear the SmartPhotoLayoutManager bitmap pool
                smartPhotoLayoutManager.clearBitmapPool()

                // Log bitmap pool stats
                Log.d(TAG, "Bitmap pool stats: ${smartPhotoLayoutManager.getBitmapPoolStats()}")

                // Request garbage collection
                System.gc()

                // Allow some time for GC to complete
                delay(300)

                // Log final memory state
                logMemoryMetrics("🚨 After low memory cleanup")
            } catch (e: Exception) {
                Log.e(TAG, "Error during low memory handling", e)
            }
        }
    }

    /**
     * Check and handle bitmap pool when memory pressure is high
     * Call this periodically or when memory pressure is detected
     */
    fun checkBitmapPool(smartPhotoLayoutManager: SmartPhotoLayoutManager) {
        // Only check if we're under memory pressure
        if (memoryPressureLevel != MemoryPressureLevel.NORMAL) {
            managerScope.launch {
                try {
                    val memoryInfo = getMemoryInfo()

                    // If memory usage is high
                    if (memoryInfo.usedPercent >= MEMORY_PRESSURE_THRESHOLD_PERCENT) {
                        Log.i(TAG, "Memory pressure (${memoryInfo.usedPercent.toInt()}%), clearing bitmap pool")

                        // Clear the bitmap pool
                        smartPhotoLayoutManager.clearBitmapPool()

                        // Log bitmap pool stats after clearing
                        Log.d(TAG, "Bitmap pool cleared, current stats: ${smartPhotoLayoutManager.getBitmapPoolStats()}")

                        // Request garbage collection
                        System.gc()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking bitmap pool", e)
                }
            }
        }
    }

    /**
     * Handle trim memory events from the app (needs to be called from your Activity or Application)
     */
    fun onTrimMemory(level: Int, smartPhotoLayoutManager: SmartPhotoLayoutManager) {
        // Handle trim memory based on level
        when (level) {
            // Critical levels - clear everything
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.i(TAG, "Critical trim memory level: $level, clearing all caches")
                clearMemoryCaches()
                smartPhotoLayoutManager.clearBitmapPool()
                System.gc()
            }

            // Moderate levels - clear bitmap pool but not other caches
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.i(TAG, "Moderate trim memory level: $level, clearing bitmap pool")
                smartPhotoLayoutManager.clearBitmapPool()
            }

            // Lower levels - just log but don't clear anything
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                Log.d(TAG, "Trim memory level: $level, no action needed")
            }
        }
    }

    /**
     * Clean up the tracking map by removing ALL bitmaps except current display and 2 preloaded
     * Returns the number of entries removed
     */
    private fun cleanupTrackingMap(): Int {
        // Step 1: Find the current display photo and up to 2 preloaded photos to keep
        val keysToKeep = mutableListOf<String>()

        // Find current display photo (we must keep this)
        activeBitmaps.keys.find { it.startsWith("display:") }?.let {
            keysToKeep.add(it)
        }

        // Find up to 2 preloaded photos to keep
        activeBitmaps.keys
            .filter { it.startsWith("preload:") }
            .take(5)
            .forEach { keysToKeep.add(it) }

        // Step 2: Remove ALL other bitmaps
        val beforeCount = activeBitmaps.size
        val keysToRemove = activeBitmaps.keys.filter { !keysToKeep.contains(it) }

        // Remove all non-essential bitmaps
        keysToRemove.forEach { key ->
            activeBitmaps.remove(key)
        }

        val removedCount = beforeCount - activeBitmaps.size

        if (removedCount > 0) {
            Log.d(TAG, "🧹 Cleaned up $removedCount bitmaps, kept ${activeBitmaps.size} essential bitmaps")
        }

        return removedCount
    }

    /**
     * Log memory metrics for Smart Fill operations specifically
     */
    fun logSmartFillMemoryMetrics(photoCount: Int, templateType: String) {
        val memInfo = getMemoryInfo()
        val trackedBitmaps = activeBitmaps.size
        val trackedBitmapMem = calculateTotalTrackedBitmapMemory()

        Log.i(TAG, """
        🖼️ SMART FILL MEMORY REPORT ($templateType - $photoCount photo${if(photoCount>1)"s" else ""}):
        • Java heap: ${formatBytes(memInfo.usedMemory)} / ${formatBytes(memInfo.maxMemory)} (${decimalFormat.format(memInfo.usedPercent)}%)
        • Memory pressure: $memoryPressureLevel
        • Tracked bitmaps: $trackedBitmaps
        • Estimated bitmap memory: ${formatBytes(trackedBitmapMem)}
    """.trimIndent())
    }

    data class MemoryInfo(
        val maxMemory: Long,
        val totalMemory: Long,
        val freeMemory: Long,
        val usedMemory: Long,
        val usedPercent: Float,
        val trackedBitmaps: Int
    )

    fun cleanup() {
        try {
            Log.i(TAG, "📊 BitmapMemoryManager: Cleanup called - cancelling monitoring tasks only")
            isMonitoringActive = false
            // Don't cancel schedulerJob here
            managerJob.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error during BitmapMemoryManager cleanup", e)
        }
    }
}