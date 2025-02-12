package com.example.screensaver.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class PhotoCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var maxCachedPhotos: Int = 10 // Default value
    private val cachedPhotosQueue = ConcurrentLinkedQueue<String>()
    // Add CacheStatus enum
    enum class CacheStatus(val message: String) {
        IDLE("Cache idle"),
        WRITING("Writing to cache"),
        READING("Reading from cache"),
        ERROR("Cache error occurred"),
        CLEANING("Cleaning cache"),
        LOW_SPACE("Low cache space"),
        INITIALIZING("Initializing cache"),
        PRELOADING("Preloading photos"),
        PRELOAD_COMPLETE("Preload complete"),
        PRELOAD_CANCELLED("Preload cancelled")
    }

    // Add status monitoring
    private val _cacheStatus = MutableStateFlow<CacheStatus>(CacheStatus.IDLE)
    val cacheStatus: StateFlow<CacheStatus> = _cacheStatus

    private val preloadQueue = ConcurrentLinkedQueue<String>()
    private val preloadJob = AtomicReference<Job?>()
    private val preloadedPhotos = ConcurrentHashMap<String, Long>()

    // Add cache statistics
    data class CacheStats(
        val totalSize: Long = 0,
        val fileCount: Int = 0,
        val lastUpdated: Long = 0,
        val preloadedCount: Int = 0,
        val preloadQueueSize: Int = 0
    )

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats

    // Existing properties
    private val cacheDir = File(context.cacheDir, "photos")
    private val lastPhotoFile = File(cacheDir, "last_photo.jpg")
    private val lastPhotoUrlFile = File(cacheDir, "last_photo_url.txt")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val MAX_CACHE_SIZE = 50 * 1024 * 1024 // 50MB
    private val MAX_CACHE_AGE = 24 * 60 * 60 * 1000 // 24 hours

    private val PREFS_NAME = "photo_cache_prefs"
    private val KEY_HAS_PHOTOS = "has_photos"
    private val KEY_LAST_KNOWN_PHOTO = "last_known_photo"

    private var cachedBitmapInMemory: Bitmap? = null

    companion object {
        private const val TAG = "PhotoCache"
    }

    init {
        _cacheStatus.value = CacheStatus.INITIALIZING
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        updateCacheStats()
        _cacheStatus.value = CacheStatus.IDLE
    }

    fun savePhotoState(hasPhotos: Boolean, lastPhotoUrl: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HAS_PHOTOS, hasPhotos)
            .putString(KEY_LAST_KNOWN_PHOTO, lastPhotoUrl)
            .apply()
    }

    private fun updateCacheStats() {
        // Only update if really needed, not on every operation
        if (_cacheStatus.value == CacheStatus.IDLE) return
        try {
            var totalSize = 0L
            var fileCount = 0
            cacheDir.listFiles()?.forEach { file ->
                totalSize += file.length()
                fileCount++
            }
            _cacheStats.value = CacheStats(
                totalSize = totalSize,
                fileCount = fileCount,
                lastUpdated = System.currentTimeMillis(),
                preloadedCount = preloadedPhotos.size,
                preloadQueueSize = preloadQueue.size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cache stats", e)
        }
    }

    fun setMaxCachedPhotos(count: Int) {
        maxCachedPhotos = count
        performSmartCleanup() // Clean up if we're over the new limit
    }

    suspend fun cacheLastPhotoBitmap(bitmap: Bitmap) = withContext(Dispatchers.IO + NonCancellable) {
        try {
            _cacheStatus.value = CacheStatus.WRITING
            FileOutputStream(lastPhotoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) // Reduced quality for speed
            }
            cachedBitmapInMemory = bitmap // Keep in memory
            _cacheStatus.value = CacheStatus.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Error caching photo bitmap", e)
            _cacheStatus.value = CacheStatus.ERROR
            throw e
        }
    }

    fun performSmartCleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                _cacheStatus.value = CacheStatus.CLEANING

                // Keep only the most recent photos up to maxCachedPhotos
                val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@launch

                // Always keep last photo file
                val filesToKeep = files.take(maxCachedPhotos)
                val filesToDelete = files.drop(maxCachedPhotos).filter {
                    it != lastPhotoFile && it != lastPhotoUrlFile
                }

                filesToDelete.forEach { it.delete() }

                // Update cached photos queue
                cachedPhotosQueue.clear()
                filesToKeep.forEach { file ->
                    cachedPhotosQueue.offer(file.nameWithoutExtension)
                }

                updateCacheStats()
                _cacheStatus.value = CacheStatus.IDLE
            } catch (e: Exception) {
                Log.e(TAG, "Error during smart cleanup", e)
                _cacheStatus.value = CacheStatus.ERROR
            }
        }
    }

    fun cleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                _cacheStatus.value = CacheStatus.CLEANING
                if (lastPhotoFile.exists()) {
                    lastPhotoFile.delete()
                }
                if (lastPhotoUrlFile.exists()) {
                    lastPhotoUrlFile.delete()
                }
                updateCacheStats()
                _cacheStatus.value = CacheStatus.IDLE
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up cache", e)
                _cacheStatus.value = CacheStatus.ERROR
            }
        }
    }
}