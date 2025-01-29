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

@Singleton
class PhotoCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Add CacheStatus enum
    enum class CacheStatus(val message: String) {
        IDLE("Cache idle"),
        WRITING("Writing to cache"),
        READING("Reading from cache"),
        ERROR("Cache error occurred"),
        CLEANING("Cleaning cache"),
        LOW_SPACE("Low cache space"),
        INITIALIZING("Initializing cache")
    }

    // Add status monitoring
    private val _cacheStatus = MutableStateFlow<CacheStatus>(CacheStatus.IDLE)
    val cacheStatus: StateFlow<CacheStatus> = _cacheStatus

    // Add cache statistics
    data class CacheStats(
        val totalSize: Long = 0,
        val fileCount: Int = 0,
        val lastUpdated: Long = 0
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

    private fun updateCacheStats() {
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
                lastUpdated = System.currentTimeMillis()
            )

            // Check for low space
            if (totalSize > MAX_CACHE_SIZE * 0.9) { // 90% full
                _cacheStatus.value = CacheStatus.LOW_SPACE
                performSmartCleanup()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cache stats", e)
        }
    }

    suspend fun cacheLastPhotoUrl(url: String) = withContext(Dispatchers.IO) {
        try {
            _cacheStatus.value = CacheStatus.WRITING
            lastPhotoUrlFile.writeText(url)
            Log.d(TAG, "Cached photo URL: $url")
            updateCacheStats()
            _cacheStatus.value = CacheStatus.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Error caching photo URL", e)
            _cacheStatus.value = CacheStatus.ERROR
            throw e
        }
    }

    fun getLastCachedPhotoUrl(): String? {
        return try {
            _cacheStatus.value = CacheStatus.READING
            if (lastPhotoUrlFile.exists()) {
                lastPhotoUrlFile.readText().also {
                    _cacheStatus.value = CacheStatus.IDLE
                }
            } else {
                _cacheStatus.value = CacheStatus.IDLE
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached photo URL", e)
            _cacheStatus.value = CacheStatus.ERROR
            null
        }
    }

    suspend fun cacheLastPhotoBitmap(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            _cacheStatus.value = CacheStatus.WRITING
            FileOutputStream(lastPhotoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "Cached photo bitmap successfully")
            updateCacheStats()
            _cacheStatus.value = CacheStatus.IDLE
        } catch (e: Exception) {
            Log.e(TAG, "Error caching photo bitmap", e)
            _cacheStatus.value = CacheStatus.ERROR
            throw e
        }
    }

    suspend fun getLastCachedPhotoBitmap(): Bitmap? = withContext(Dispatchers.IO) {
        if (!lastPhotoFile.exists()) return@withContext null

        try {
            _cacheStatus.value = CacheStatus.READING
            BitmapFactory.decodeFile(lastPhotoFile.absolutePath).also {
                _cacheStatus.value = CacheStatus.IDLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached photo bitmap", e)
            _cacheStatus.value = CacheStatus.ERROR
            null
        }
    }

    fun performSmartCleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                _cacheStatus.value = CacheStatus.CLEANING
                val currentTime = System.currentTimeMillis()
                var totalSize = 0L

                cacheDir.listFiles()?.sortedBy { it.lastModified() }?.forEach { file ->
                    val age = currentTime - file.lastModified()
                    val isLastPhoto = file == lastPhotoFile || file == lastPhotoUrlFile

                    when {
                        isLastPhoto -> totalSize += file.length()
                        age > MAX_CACHE_AGE -> file.delete()
                        totalSize + file.length() > MAX_CACHE_SIZE -> file.delete()
                        else -> totalSize += file.length()
                    }
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

    // Add debug information method
    fun getCacheDebugInfo(): String {
        val stats = _cacheStats.value
        return buildString {
            append("Cache Status: ${_cacheStatus.value}\n")
            append("Total Size: ${stats.totalSize / 1024}KB\n")
            append("File Count: ${stats.fileCount}\n")
            append("Last Updated: ${java.util.Date(stats.lastUpdated)}\n")
            append("Cache Directory: ${cacheDir.absolutePath}\n")
        }
    }
}