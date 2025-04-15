package com.photostreamr.ui

import android.content.Context
import android.util.Log
import com.bumptech.glide.Glide
import kotlinx.coroutines.*
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Tracks and manages Glide's disk cache to prevent excessive storage usage
 * Works alongside BitmapMemoryManager for complete memory management
 */
@Singleton
class DiskCacheManager @Inject constructor(
    private val context: Context
) {
    private val TAG = "DiskCacheManager"

    // Coroutine management - match BitmapMemoryManager structure
    private var managerJob = SupervisorJob()
    private var managerScope = CoroutineScope(Dispatchers.IO + managerJob)

    // Cache metrics tracking
    private var lastCleanupTimestamp = 0L
    private var bytesFreedLastCleanup = 0L
    private var totalBytesFreed = 0L
    private var cleanupCount = 0

    // Thresholds and intervals
    private val CLEANUP_SIZE_THRESHOLD_MB = 20 // Clean when disk cache > 100MB
    private val MIN_CLEANUP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1) // Wait 30 min between cleanups
    private val CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1) // Check every 15 minutes
    private val LOG_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1) // Log every 5 minutes

    // Tracking history (to detect cache growth rates)
    private data class CacheSnapshot(
        val timestamp: Long,
        val sizeBytes: Long
    )

    private val cacheHistory = LinkedList<CacheSnapshot>()
    private val MAX_HISTORY_SIZE = 10
    private val formatter = DecimalFormat("#,###.##")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // Current cache size
    private var currentCacheSize = 0L

    // Track if tracking is active
    private var isTrackingActive = false

    init {
        startDiskCacheTracking()

        managerScope.launch {
            delay(5000) // Wait 5 seconds after app start
            Log.i(TAG, "💾 TESTING: Forcing cleanup to verify functionality")
            forceCleanupNow()
        }
    }

    /**
     * Start background monitoring of disk cache size
     */
    private fun startDiskCacheTracking() {
        // Always force start regardless of flag
        Log.i(TAG, "💾 Starting disk cache tracking")

        // Reset flag
        isTrackingActive = true

        // First get initial measurement
        managerScope.launch {
            try {
                measureDiskCacheSize()

                // Then start periodic measurements and cleanup checks
                while (isActive) {
                    try {
                        // Measure disk cache size
                        measureDiskCacheSize()

                        // Check if we need to perform cleanup
                        val cacheSizeMB = currentCacheSize / (1024 * 1024)
                        if (cacheSizeMB >= CLEANUP_SIZE_THRESHOLD_MB && canPerformCleanup()) {
                            Log.i(TAG, "💾 Disk cache size (${formatBytes(currentCacheSize)}) exceeds threshold, performing cleanup")
                            cleanupDiskCache()
                        } else {
                            Log.d(TAG, "💾 Disk cache size: ${formatBytes(currentCacheSize)}")
                        }

                        // Wait before next check
                        delay(CHECK_INTERVAL_MS)
                    } catch (e: CancellationException) {
                        isTrackingActive = false  // Set to false when cancelled
                        Log.d(TAG, "💾 Disk cache tracking cancelled")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in disk cache tracking", e)
                        delay(60000) // Wait a minute and try again if there was an error
                    }
                }
            } catch (e: CancellationException) {
                // Expected during cancellation
                isTrackingActive = false  // Set to false when cancelled
                Log.d(TAG, "💾 Disk cache tracking cancelled in outer block")
            } catch (e: Exception) {
                Log.e(TAG, "Critical error in disk cache tracking", e)
                isTrackingActive = false  // Set to false on error
            }
        }

        // Separate logging cycle for visibility
        managerScope.launch {
            while (isActive) {
                try {
                    logDiskCacheMetrics()
                    delay(LOG_INTERVAL_MS)
                } catch (e: CancellationException) {
                    // Expected during cancellation, just break
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in disk cache logging", e)
                    delay(60000)
                }
            }
        }
    }

    /**
     * Measure the current size of the disk cache
     */
    private suspend fun measureDiskCacheSize() {
        withContext(Dispatchers.IO) {
            try {
                val size = calculateDiskCacheSize()

                // Update current size
                currentCacheSize = size

                // Add to history
                addCacheSnapshot(size)

            } catch (e: Exception) {
                Log.e(TAG, "Error measuring disk cache size", e)
            }
        }
    }

    /**
     * Add a cache size snapshot to history
     */
    private fun addCacheSnapshot(size: Long) {
        synchronized(cacheHistory) {
            cacheHistory.add(CacheSnapshot(System.currentTimeMillis(), size))
            if (cacheHistory.size > MAX_HISTORY_SIZE) {
                cacheHistory.removeFirst()
            }
        }
    }

    /**
     * Check if enough time has passed since last cleanup
     */
    fun canPerformCleanup(): Boolean {
        return System.currentTimeMillis() - lastCleanupTimestamp > MIN_CLEANUP_INTERVAL_MS
    }

    /**
     * Perform disk cache cleanup
     */
    fun cleanupDiskCache() {
        // Check if we can actually clean now
        if (!canPerformCleanup()) {
            Log.d(TAG, "💾 Skipping disk cleanup - too soon since last cleanup")
            return
        }

        managerScope.launch {
            try {
                val startTime = System.currentTimeMillis()

                // Get size before cleanup
                val beforeSize = currentCacheSize
                Log.i(TAG, "💾 Starting disk cache cleanup (${formatBytes(beforeSize)})")

                // Clear Glide's disk cache on IO thread
                withContext(Dispatchers.IO) {
                    Glide.get(context).clearDiskCache()
                }

                // Small delay to allow disk operations to complete
                delay(500)

                // Measure new size
                val afterSize = calculateDiskCacheSize()
                currentCacheSize = afterSize

                // Calculate freed space
                val freedBytes = max(0, beforeSize - afterSize)
                bytesFreedLastCleanup = freedBytes
                totalBytesFreed += freedBytes
                cleanupCount++

                // Update timestamp
                lastCleanupTimestamp = System.currentTimeMillis()
                val duration = lastCleanupTimestamp - startTime

                // Update tracking history
                addCacheSnapshot(afterSize)

                // Log the results
                Log.i(TAG, """
                    💾 Disk cache cleanup complete:
                    • Before: ${formatBytes(beforeSize)}
                    • After:  ${formatBytes(afterSize)}
                    • Freed:  ${formatBytes(freedBytes)}
                    • Duration: ${duration}ms
                    • Total freed: ${formatBytes(totalBytesFreed)} in $cleanupCount cleanups
                """.trimIndent())

            } catch (e: Exception) {
                Log.e(TAG, "Error during disk cache cleanup", e)
            }
        }
    }

    /**
     * Log disk cache metrics and history
     */
    private fun logDiskCacheMetrics() {
        try {
            val cacheSizeMB = currentCacheSize / (1024 * 1024)
            val cacheDir = Glide.getPhotoCacheDir(context)
            val fileCount = cacheDir?.listFiles()?.size ?: 0
            val trend = calculateCacheTrend()

            Log.i(TAG, """
                💾 DISK CACHE STATUS:
                • Current size: ${formatBytes(currentCacheSize)} ($cacheSizeMB MB)
                • Files: $fileCount
                • Last cleanup: ${if (lastCleanupTimestamp > 0) formatTimestamp(lastCleanupTimestamp) else "never"}
                • Freed last cleanup: ${formatBytes(bytesFreedLastCleanup)}
                • Total freed: ${formatBytes(totalBytesFreed)}
                • Cleanup count: $cleanupCount
                • Location: ${cacheDir?.absolutePath ?: "unknown"}
                
                $trend
            """.trimIndent())

        } catch (e: Exception) {
            Log.e(TAG, "Error logging disk cache metrics", e)
        }
    }

    /**
     * Calculate cache growth trend based on history
     */
    private fun calculateCacheTrend(): String {
        synchronized(cacheHistory) {
            if (cacheHistory.size < 2) {
                return "📈 Trend: Insufficient data for trend analysis"
            }

            val oldest = cacheHistory.first
            val newest = cacheHistory.last

            val sizeDiffBytes = newest.sizeBytes - oldest.sizeBytes
            val timeDiffMs = newest.timestamp - oldest.timestamp

            // Calculate growth rate
            val growthRatePerHour = if (timeDiffMs > 0) {
                (sizeDiffBytes.toDouble() / timeDiffMs) * TimeUnit.HOURS.toMillis(1)
            } else {
                0.0
            }

            val growthRateMBPerHour = growthRatePerHour / (1024 * 1024)

            val trend = when {
                growthRateMBPerHour > 10 -> "🔴 RAPIDLY GROWING"
                growthRateMBPerHour > 1 -> "🟠 GROWING"
                growthRateMBPerHour > -1 && growthRateMBPerHour < 1 -> "🟢 STABLE"
                growthRateMBPerHour < -10 -> "🔵 RAPIDLY SHRINKING"
                else -> "🔵 SHRINKING"
            }

            return """
                📈 DISK CACHE TREND ($trend):
                • Growth rate: ${formatter.format(growthRateMBPerHour)} MB/hour
                • Size change: ${formatBytes(sizeDiffBytes)} over ${formatDuration(timeDiffMs)}
            """.trimIndent()
        }
    }

    /**
     * Format a timestamp to a readable date/time
     */
    private fun formatTimestamp(timestamp: Long): String {
        return try {
            dateFormat.format(Date(timestamp))
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Format a time duration in milliseconds to a readable string
     */
    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Force a disk cache cleanup now (use for manual testing)
     */
    fun forceCleanupNow() {
        Log.i(TAG, "💾 Forcing immediate disk cache cleanup")
        lastCleanupTimestamp = 0 // Reset timestamp to ensure we can clean
        cleanupDiskCache()
    }

    /**
     * Calculate the total size of Glide's disk cache
     */
    private fun calculateDiskCacheSize(): Long {
        var totalSize = 0L
        try {
            val glideCacheDir = Glide.getPhotoCacheDir(context)
            if (glideCacheDir != null && glideCacheDir.exists()) {
                totalSize = calculateDirSize(glideCacheDir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating disk cache size", e)
        }
        return totalSize
    }

    /**
     * Calculate the size of a directory recursively
     */
    private fun calculateDirSize(directory: File): Long {
        var size = 0L
        try {
            val files = directory.listFiles() ?: return 0

            for (file in files) {
                size += if (file.isDirectory) {
                    calculateDirSize(file)
                } else {
                    file.length()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating directory size", e)
        }
        return size
    }

    /**
     * Format bytes to a human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        if (bytes < 1024) return "$bytes B"

        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val value = bytes.toDouble() / Math.pow(1024.0, digitGroups.toDouble())

        return "${formatter.format(value)} ${units[digitGroups]}"
    }

    /**
     * Get current disk cache size in MB
     */
    fun getCurrentCacheSizeMB(): Int {
        return (currentCacheSize / (1024 * 1024)).toInt()
    }

    /**
     * Resume disk cache tracking after it has been stopped
     * Call this when returning from settings or when app resumes
     */
    fun resumeTracking(forceCleanupNow: Boolean = false) {  // Changed default to false
        Log.i(TAG, "💾 Resuming disk cache tracking")

        // Cancel any existing job
        managerJob.cancel()

        // Wait for a brief moment to ensure cancellation completes
        try {
            Thread.sleep(50)
        } catch (e: Exception) {
            // Ignore interruption
        }

        // Force reset tracking flag
        isTrackingActive = false

        // Recreate job and scope
        managerJob = SupervisorJob()
        managerScope = CoroutineScope(Dispatchers.IO + managerJob)

        // Start fresh tracking
        startDiskCacheTracking()

        // Only perform cleanup if explicitly requested
        if (forceCleanupNow) {
            Log.i(TAG, "💾 Forcing immediate disk cache cleanup (explicitly requested)")
            lastCleanupTimestamp = 0
            cleanupDiskCache()
        } else {
            Log.d(TAG, "💾 Resuming tracking without cleanup")
        }
    }

    /**
     * Clean up resources when app is shutting down
     */
    fun cleanup() {
        try {
            Log.i(TAG, "💾 DiskCacheManager: Cleanup called - cancelling background tasks")
            isTrackingActive = false  // Explicitly reset flag first
            managerJob.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "💾 Error during DiskCacheManager cleanup", e)
        }
    }
}