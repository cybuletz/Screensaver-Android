package com.example.screensaver.photos

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

@Singleton
class PersistentPhotoCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir = File(context.filesDir, "cached_photos").apply {
        if (!exists()) mkdirs()
    }

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
                CoroutineExceptionHandler { _, e ->
                    Log.e(TAG, "Error in cache coroutine", e)
                }
    )

    // For parallel processing
    private val cacheDispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    // Cache status tracking
    private val _cachingProgress = MutableStateFlow<CachingProgress>(CachingProgress.Idle)
    val cachingProgress: StateFlow<CachingProgress> = _cachingProgress

    // Map of original URIs to cached file URIs
    private val uriMapping = ConcurrentHashMap<String, String>()

    // File size tracking
    private val _fileSizes = ConcurrentHashMap<String, Long>()
    val fileSizes: Map<String, Long> get() = _fileSizes
    private val _totalCacheSize = MutableStateFlow(0L)
    val totalCacheSize: StateFlow<Long> = _totalCacheSize.asStateFlow()

    companion object {
        private const val TAG = "PersistentPhotoCache"
        private const val CACHE_VERSION = 1 // Increment when cache format changes
    }

    init {
        // Load existing URI mappings
        scope.launch {
            loadUriMappings()
            calculateTotalCacheSize()
        }
    }

    private suspend fun loadUriMappings() {
        withContext(Dispatchers.IO) {
            try {
                val mappingFile = File(cacheDir, "uri_mappings.txt")
                if (mappingFile.exists()) {
                    mappingFile.readLines().forEach { line ->
                        val parts = line.split(" -> ")
                        if (parts.size == 2) {
                            uriMapping[parts[0]] = parts[1]
                        }
                    }
                }
                Log.d(TAG, "Loaded ${uriMapping.size} URI mappings from disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load URI mappings", e)
            }
        }
    }

    private suspend fun calculateTotalCacheSize() {
        withContext(Dispatchers.IO) {
            try {
                var totalSize = 0L

                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name != "uri_mappings.txt") {
                        val size = file.length()
                        val uri = getFileProviderUri(file)
                        _fileSizes[uri] = size
                        totalSize += size
                    }
                }

                _totalCacheSize.value = totalSize
                Log.d(TAG, "Total cache size: ${formatFileSize(totalSize)}")
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating cache size", e)
            }
        }
    }

    private suspend fun saveUriMappings() {
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                val mappingFile = File(cacheDir, "uri_mappings.txt")
                FileOutputStream(mappingFile).use { out ->
                    uriMapping.forEach { (originalUri, cachedUri) ->
                        out.write("$originalUri -> $cachedUri\n".toByteArray())
                    }
                }
                Log.d(TAG, "Saved ${uriMapping.size} URI mappings to disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save URI mappings", e)
            }
        }
    }

    /**
     * Get a cached URI for a photo if it exists
     */
    fun getCachedPhotoUri(originalUri: String): String? {
        val cachedUri = uriMapping[originalUri]
        if (cachedUri != null) {
            // Verify the file still exists
            val uri = Uri.parse(cachedUri)
            if (uri.scheme == "file") {
                val file = File(uri.path!!)
                if (file.exists() && file.length() > 0) {
                    return cachedUri
                } else {
                    // File is gone, remove from mapping
                    uriMapping.remove(originalUri)
                    _fileSizes.remove(cachedUri)
                    scope.launch {
                        saveUriMappings()
                        calculateTotalCacheSize()
                    }
                }
            } else {
                return cachedUri
            }
        }
        return null
    }

    /**
     * Get FileProvider URI for a cached file
     */
    private fun getFileProviderUri(file: File): String {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        ).toString()
    }

    /**
     * Format bytes to human-readable size
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(bytes / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Update file size information for a URI
     */
    private fun updateFileSize(uri: String) {
        try {
            val fileUri = Uri.parse(uri)
            if (fileUri.scheme == "file") {
                val file = File(fileUri.path!!)
                if (file.exists()) {
                    val size = file.length()
                    _fileSizes[uri] = size
                    _totalCacheSize.value = _fileSizes.values.sum()
                    Log.d(TAG, "Updated file size for $uri: ${formatFileSize(size)}, total: ${formatFileSize(_totalCacheSize.value)}")
                }
            } else if (fileUri.scheme == "content") {
                try {
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val size = it.statSize
                        _fileSizes[uri] = size
                        _totalCacheSize.value = _fileSizes.values.sum()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not get content URI size", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size for $uri", e)
        }
    }

    /**
     * Cache a list of photos with progress tracking
     */
    fun cachePhotos(photoUris: List<String>): Flow<CachingProgress> {
        if (photoUris.isEmpty()) {
            return flowOf(CachingProgress.Complete(0, 0, 0, 0L))
        }

        return flow {
            emit(CachingProgress.Starting(photoUris.size))

            val alreadyCached = photoUris.count { uriMapping.containsKey(it) }
            val toCacheCount = photoUris.size - alreadyCached

            if (toCacheCount <= 0) {
                emit(CachingProgress.Complete(photoUris.size, 0, alreadyCached, _totalCacheSize.value))
                return@flow
            }

            // Filter out already cached URIs
            val urisToCache = photoUris.filter { !uriMapping.containsKey(it) }
            val results = ConcurrentHashMap<String, CacheResult>()
            var completed = 0
            var errors = 0

            // Update global progress
            _cachingProgress.value = CachingProgress.InProgress(0, 0, urisToCache.size, 0f, 0L)

            try {
                withContext(cacheDispatcher) {
                    urisToCache.map { uri ->
                        async {
                            try {
                                val result = cachePhotoInternal(uri)
                                results[uri] = result

                                if (result is CacheResult.Success) {
                                    completed++
                                    updateFileSize(result.cachedUri)

                                    // Get the current file size for progress updates
                                    val currentFileSize = _fileSizes[result.cachedUri] ?: 0L

                                    // Calculate progress
                                    val progress = (completed + errors).toFloat() / urisToCache.size
                                    _cachingProgress.value = CachingProgress.InProgress(
                                        completed, errors, urisToCache.size, progress, currentFileSize
                                    )
                                } else {
                                    errors++

                                    // Calculate progress
                                    val progress = (completed + errors).toFloat() / urisToCache.size
                                    _cachingProgress.value = CachingProgress.InProgress(
                                        completed, errors, urisToCache.size, progress, 0L
                                    )
                                }

                                // Emit progress updates at reasonable intervals
                                if ((completed + errors) % 5 == 0 || (completed + errors) == urisToCache.size) {
                                    val latestFileSize = if (result is CacheResult.Success)
                                        _fileSizes[result.cachedUri] ?: 0L
                                    else
                                        0L

                                    emit(
                                        CachingProgress.InProgress(
                                            completed, errors, urisToCache.size,
                                            (completed + errors).toFloat() / urisToCache.size,
                                            latestFileSize
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error caching photo: $uri", e)
                                errors++
                                results[uri] = CacheResult.Error(e.message ?: "Unknown error")
                            }
                        }
                    }.awaitAll() // Wait for all caching operations to complete
                }

                // Save URI mappings after batch complete
                saveUriMappings()

                // Final progress update
                emit(CachingProgress.Complete(completed, errors, alreadyCached, _totalCacheSize.value))
                _cachingProgress.value = CachingProgress.Complete(completed, errors, alreadyCached, _totalCacheSize.value)
            } catch (e: Exception) {
                Log.e(TAG, "Error during batch caching", e)
                emit(CachingProgress.Failed(e.message ?: "Unknown error", completed, errors))
                _cachingProgress.value = CachingProgress.Failed(e.message ?: "Unknown error", completed, errors)
            }
        }
    }

    /**
     * Internal method to cache a single photo
     */
    private suspend fun cachePhotoInternal(originalUri: String): CacheResult {
        return withContext(Dispatchers.IO) {
            try {
                // Generate a stable filename
                val fileName = "photo_${abs(originalUri.hashCode())}.jpg"
                val cachedFile = File(cacheDir, fileName)

                // If already cached, return success
                if (cachedFile.exists() && cachedFile.length() > 0) {
                    val cachedUri = getFileProviderUri(cachedFile)
                    uriMapping[originalUri] = cachedUri
                    updateFileSize(cachedUri)
                    return@withContext CacheResult.Success(cachedUri)
                }

                // Get device display metrics for optimal sizing
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // Use the larger dimension to ensure we have enough resolution
                // but don't go unnecessarily large
                val targetSize = maxOf(screenWidth, screenHeight)

                // Try to download and cache using Glide with optimal size
                try {
                    val future = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(Uri.parse(originalUri))
                        .override(targetSize)  // Set max width to screen width
                        .submit()

                    val bitmap = future.get()
                    if (bitmap != null) {
                        FileOutputStream(cachedFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }

                        if (cachedFile.exists() && cachedFile.length() > 0) {
                            val cachedUri = getFileProviderUri(cachedFile)
                            uriMapping[originalUri] = cachedUri
                            updateFileSize(cachedUri)
                            Log.d(TAG, "Successfully cached photo: $originalUri -> $cachedUri (${formatFileSize(cachedFile.length())}) [${bitmap.width}x${bitmap.height}]")
                            return@withContext CacheResult.Success(cachedUri)
                        }
                    }
                    return@withContext CacheResult.Error("Failed to get bitmap from Glide")
                } catch (e: Exception) {
                    Log.e(TAG, "Error caching with Glide: ${e.message}")

                    // Fallback: try to download directly from content provider
                    try {
                        val uri = Uri.parse(originalUri)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cachedFile.outputStream().use { output ->
                                input.copyTo(output)
                            }

                            val cachedUri = getFileProviderUri(cachedFile)
                            uriMapping[originalUri] = cachedUri
                            updateFileSize(cachedUri)
                            return@withContext CacheResult.Success(cachedUri)
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback caching also failed", e2)
                        return@withContext CacheResult.Error("Both caching methods failed: ${e.message}, ${e2.message}")
                    }
                }

                return@withContext CacheResult.Error("Failed to cache photo")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error caching photo", e)
                return@withContext CacheResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Get all cached file URIs
     */
    fun getAllCachedUris(): List<String> {
        return uriMapping.values.toList()
    }

    /**
     * Check if a URI is already cached
     */
    fun isUriCached(uri: String): Boolean {
        return uriMapping.containsKey(uri)
    }

    /**
     * Clean up the cache (call when needed)
     */
    fun cleanup() {
        scope.launch(Dispatchers.IO + NonCancellable) {
            try {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.name != "uri_mappings.txt") {
                        file.delete()
                    }
                }
                uriMapping.clear()
                _fileSizes.clear()
                _totalCacheSize.value = 0L
                saveUriMappings()
                Log.d(TAG, "Cache cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up cache", e)
            }
        }
    }

    /**
     * Result classes for cache operations
     */
    sealed class CacheResult {
        data class Success(val cachedUri: String) : CacheResult()
        data class Error(val message: String) : CacheResult()
    }

    /**
     * Progress tracking for batch caching
     */
    sealed class CachingProgress {
        object Idle : CachingProgress()
        data class Starting(val total: Int) : CachingProgress()
        data class InProgress(
            val completed: Int,
            val errors: Int,
            val total: Int,
            val progress: Float,
            val currentFileSize: Long = 0L
        ) : CachingProgress()
        data class Complete(
            val succeeded: Int,
            val failed: Int,
            val alreadyCached: Int,
            val totalSizeBytes: Long = 0L
        ) : CachingProgress()
        data class Failed(
            val reason: String,
            val completed: Int,
            val errors: Int
        ) : CachingProgress()
    }
}