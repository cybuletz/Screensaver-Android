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
        scope.launch {
            initializeCacheDirectory()
        }
    }

    private suspend fun initializeCacheDirectory() = withContext(Dispatchers.IO) {
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

    fun getPhotoState(): Pair<Boolean, String?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Pair(
            prefs.getBoolean(KEY_HAS_PHOTOS, false),
            prefs.getString(KEY_LAST_KNOWN_PHOTO, null)
        )
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
                lastUpdated = System.currentTimeMillis(),
                preloadedCount = preloadedPhotos.size,
                preloadQueueSize = preloadQueue.size
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

    suspend fun preloadPhoto(url: String): Boolean {
        if (isPhotoCached(url) || url in preloadQueue) {
            return false
        }

        try {
            _cacheStatus.value = CacheStatus.PRELOADING
            preloadQueue.offer(url)
            startPreloading()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error queuing photo for preload: $url", e)
            _cacheStatus.value = CacheStatus.ERROR
            return false
        }
    }

    private suspend fun downloadPhoto(url: String): Bitmap {
        return withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                BitmapFactory.decodeStream(connection.inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading photo: $url", e)
                throw e
            }
        }
    }

    private suspend fun cachePhotoBitmap(url: String, bitmap: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                // Check if we need to remove old cached photos
                while (cachedPhotosQueue.size >= maxCachedPhotos) {
                    val oldestUrl = cachedPhotosQueue.poll()
                    oldestUrl?.let {
                        getPhotoFile(it).delete()
                    }
                }

                val file = getPhotoFile(url)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                cachedPhotosQueue.offer(url)
                updateCacheStats()
            } catch (e: Exception) {
                Log.e(TAG, "Error caching photo bitmap", e)
                throw e
            }
        }
    }

    private fun startPreloading() {
        if (preloadJob.get()?.isActive == true) return

        preloadJob.set(scope.launch {
            try {
                while (isActive && preloadQueue.isNotEmpty()) {
                    val url = preloadQueue.poll() ?: continue
                    try {
                        if (!isPhotoCached(url)) {
                            // Download and cache photo
                            val bitmap = downloadPhoto(url)
                            cachePhotoBitmap(url, bitmap)
                            preloadedPhotos[url] = System.currentTimeMillis()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preloading photo: $url", e)
                    }
                }
                _cacheStatus.value = CacheStatus.PRELOAD_COMPLETE
            } catch (e: Exception) {
                Log.e(TAG, "Error in preload job", e)
                _cacheStatus.value = CacheStatus.ERROR
            }
        })
    }

    fun setMaxCachedPhotos(count: Int) {
        maxCachedPhotos = count
        performSmartCleanup() // Clean up if we're over the new limit
    }

    fun cancelPreloading() {
        preloadJob.get()?.cancel()
        preloadQueue.clear()
        _cacheStatus.value = CacheStatus.PRELOAD_CANCELLED
    }

    private fun isPhotoCached(url: String): Boolean {
        return getPhotoFile(url).exists()
    }

    private fun getPhotoFile(url: String): File {
        val fileName = url.hashCode().toString()
        return File(cacheDir, fileName)
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

    suspend fun cacheLastPhotoBitmap(bitmap: Bitmap) = withContext(Dispatchers.IO + NonCancellable) {
        try {
            _cacheStatus.value = CacheStatus.WRITING
            FileOutputStream(lastPhotoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()  // Ensure immediate write
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
        try {
            _cacheStatus.value = CacheStatus.READING

            // First check memory cache
            cachedBitmapInMemory?.let {
                Log.d(TAG, "Returning cached bitmap from memory")
                return@withContext it
            }

            // Then check disk cache
            if (!lastPhotoFile.exists()) {
                Log.d(TAG, "No cached photo file exists")
                return@withContext null
            }

            BitmapFactory.decodeFile(lastPhotoFile.absolutePath)?.also {
                Log.d(TAG, "Loaded bitmap from disk cache")
                // Store in memory for faster access
                cachedBitmapInMemory = it
                _cacheStatus.value = CacheStatus.IDLE
                return@withContext it
            }

            Log.d(TAG, "Failed to load bitmap from disk")
            null
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