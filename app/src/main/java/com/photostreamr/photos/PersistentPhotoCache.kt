package com.photostreamr.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
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
    // ======== CONFIGURATION CONSTANTS ========
    // Modify these values to change quality settings

    /**
     * Quality resize factor: controls how much bigger than the screen size images should be cached
     * - 1.0 = Exactly screen size
     * - 1.15 = 15% larger than screen size (better quality)
     * - 1.5 = 50% larger than screen size (high quality but uses more storage)
     */
    companion object {
        private const val TAG = "PersistentPhotoCache"
        private const val CACHE_VERSION = 2

        // Resize factor: higher values mean more detail but larger file sizes
        // 1.15 = 15% larger than screen size for improved quality
        const val QUALITY_RESIZE_FACTOR = 1.15f

        // JPEG quality (1-100): higher values mean better quality but larger file sizes
        // 100 = Maximum quality, no compression artifacts
        const val JPEG_QUALITY = 100

        // Maximum file size in bytes (default: 2MB)
        // This is a soft limit - Compressor will try to meet this target
        const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024
    }
    // ========================================

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

    private fun isGooglePhotosUri(uri: String): Boolean {
        val lowerUri = uri.lowercase()

        // Only allow specific Google Photos URIs - everything else is considered local
        // This is a whitelist approach rather than a blacklist
        return lowerUri.startsWith("content://com.google.android.apps.photos") ||
                lowerUri.contains("googleusercontent.com/")
    }

    fun getCachedPhotoUri(originalUri: String): String {
        // ALWAYS return original URI for non-Google Photos
        if (!isGooglePhotosUri(originalUri)) {
            Log.d(TAG, "Returning original URI (not Google Photos): $originalUri")
            return originalUri
        }

        val cachedUri = uriMapping[originalUri]
        if (cachedUri != null) {
            val uri = Uri.parse(cachedUri)
            if (uri.scheme == "file") {
                val file = File(uri.path!!)
                if (file.exists() && file.length() > 0) {
                    return cachedUri
                }
                // Invalid cache entry - clean it up
                uriMapping.remove(originalUri)
                _fileSizes.remove(cachedUri)
                scope.launch {
                    saveUriMappings()
                    calculateTotalCacheSize()
                }
            } else {
                return cachedUri
            }
        }
        return originalUri  // Always return original if not cached
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

    private suspend fun cachePhotoInternal(originalUri: String): CacheResult {
        val isGoogle = isGooglePhotosUri(originalUri)
        Log.d(TAG, "Cache attempt for URI: $originalUri (isGooglePhotos: $isGoogle)")

        if (!isGoogle) {
            Log.d(TAG, "Rejected caching for non-Google Photos URI: $originalUri")
            return CacheResult.Success(originalUri)
        }

        return withContext(Dispatchers.IO) {
            try {
                val fileName = "photo_${abs(originalUri.hashCode())}.jpg"
                val cachedFile = File(cacheDir, fileName)

                if (cachedFile.exists() && cachedFile.length() > 0) {
                    val cachedUri = getFileProviderUri(cachedFile)
                    uriMapping[originalUri] = cachedUri
                    updateFileSize(cachedUri)
                    return@withContext CacheResult.Success(cachedUri)
                }

                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                try {
                    val uri = Uri.parse(originalUri)
                    val contentResolver = context.contentResolver
                    var originalWidth = 0
                    var originalHeight = 0

                    contentResolver.openInputStream(uri)?.use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(input, null, opts)
                        originalWidth = opts.outWidth
                        originalHeight = opts.outHeight
                    }

                    if (originalWidth <= 0 || originalHeight <= 0) {
                        throw Exception("Could not determine image dimensions")
                    }

                    val (targetWidth, targetHeight) = calculateTargetDimensions(
                        originalWidth = originalWidth,
                        originalHeight = originalHeight,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        resizeFactor = QUALITY_RESIZE_FACTOR
                    )

                    val sampleSize = calculateInSampleSize(originalWidth, originalHeight, targetWidth, targetHeight)

                    var bitmap: Bitmap? = null
                    contentResolver.openInputStream(uri)?.use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inJustDecodeBounds = false
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        bitmap = BitmapFactory.decodeStream(input, null, opts)
                    }

                    if (bitmap == null) {
                        throw Exception("Failed to decode image")
                    }

                    val resizedBitmap = if (bitmap!!.width != targetWidth || bitmap!!.height != targetHeight) {
                        try {
                            Bitmap.createScaledBitmap(bitmap!!, targetWidth, targetHeight, true).also {
                                bitmap?.recycle()
                                bitmap = null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error during exact resize, using sampled bitmap: ${e.message}")
                            bitmap
                        }
                    } else {
                        bitmap
                    }

                    FileOutputStream(cachedFile).use { out ->
                        resizedBitmap!!.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        out.flush()
                    }

                    resizedBitmap?.recycle()

                    if (cachedFile.exists() && cachedFile.length() > 0) {
                        val cachedUri = getFileProviderUri(cachedFile)
                        uriMapping[originalUri] = cachedUri
                        updateFileSize(cachedUri)
                        return@withContext CacheResult.Success(cachedUri)
                    } else {
                        throw Exception("Failed to write compressed image")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error optimizing image: ${e.message}")
                    try {
                        val uri = Uri.parse(originalUri)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(cachedFile).use { output ->
                                input.copyTo(output)
                                output.flush()
                            }
                            val cachedUri = getFileProviderUri(cachedFile)
                            uriMapping[originalUri] = cachedUri
                            updateFileSize(cachedUri)
                            return@withContext CacheResult.Success(cachedUri)
                        }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback caching also failed", e2)
                        return@withContext CacheResult.Error("Image processing failed: ${e.message}")
                    }
                }
                return@withContext CacheResult.Error("Failed to cache photo")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error caching photo", e)
                return@withContext CacheResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun cachePhotos(photoUris: List<String>): Flow<CachingProgress> {
        Log.d(TAG, "Cache request for ${photoUris.size} URIs")

        if (photoUris.isEmpty()) {
            return flowOf(CachingProgress.Complete(0, 0, 0, 0L))
        }

        val googlePhotosUris = photoUris.filter { uri ->
            val isGoogle = isGooglePhotosUri(uri)
            Log.d(TAG, "URI filter check: $uri (isGooglePhotos: $isGoogle)")
            isGoogle
        }

        if (googlePhotosUris.isEmpty()) {
            Log.d(TAG, "No Google Photos URIs to cache")
            return flowOf(CachingProgress.Complete(0, 0, 0, 0L))
        }

        return channelFlow {
            send(CachingProgress.Starting(googlePhotosUris.size))

            val alreadyCached = googlePhotosUris.count { uriMapping.containsKey(it) }
            val toCacheCount = googlePhotosUris.size - alreadyCached

            if (toCacheCount <= 0) {
                send(CachingProgress.Complete(googlePhotosUris.size, 0, alreadyCached, _totalCacheSize.value))
                return@channelFlow
            }

            val urisToCache = googlePhotosUris.filter { !uriMapping.containsKey(it) }
            val results = ConcurrentHashMap<String, CacheResult>(urisToCache.size)
            var completed = 0
            var errors = 0

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
                                    val currentFileSize = _fileSizes[result.cachedUri] ?: 0L

                                    val progress = (completed + errors).toFloat() / urisToCache.size
                                    _cachingProgress.value = CachingProgress.InProgress(
                                        completed, errors, urisToCache.size, progress, currentFileSize
                                    )
                                } else {
                                    errors++
                                    val progress = (completed + errors).toFloat() / urisToCache.size
                                    _cachingProgress.value = CachingProgress.InProgress(
                                        completed, errors, urisToCache.size, progress, 0L
                                    )
                                }

                                if ((completed + errors) % 5 == 0 || (completed + errors) == urisToCache.size) {
                                    val latestFileSize = if (result is CacheResult.Success)
                                        _fileSizes[result.cachedUri] ?: 0L
                                    else
                                        0L

                                    send(CachingProgress.InProgress(
                                        completed,
                                        errors,
                                        urisToCache.size,
                                        (completed + errors).toFloat() / urisToCache.size,
                                        latestFileSize
                                    ))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error caching photo: $uri", e)
                                errors++
                                results[uri] = CacheResult.Error(e.message ?: "Unknown error")
                            }
                        }
                    }.awaitAll()
                }

                saveUriMappings()
                send(CachingProgress.Complete(completed, errors, alreadyCached, _totalCacheSize.value))
                _cachingProgress.value = CachingProgress.Complete(completed, errors, alreadyCached, _totalCacheSize.value)
            } catch (e: Exception) {
                Log.e(TAG, "Error during batch caching", e)
                send(CachingProgress.Failed(e.message ?: "Unknown error", completed, errors))
                _cachingProgress.value = CachingProgress.Failed(e.message ?: "Unknown error", completed, errors)
            }
        }
    }

    /**
     * Calculate optimal sampling size to reduce memory usage during loading
     */
    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Calculate target dimensions that:
     * 1. Maintain the original aspect ratio
     * 2. Don't exceed screen dimensions × resizeFactor
     * 3. Don't upscale if image is smaller than adjusted target
     */
    private fun calculateTargetDimensions(
        originalWidth: Int,
        originalHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        resizeFactor: Float = QUALITY_RESIZE_FACTOR
    ): Pair<Int, Int> {
        // Apply resize factor to screen dimensions
        val targetScreenWidth = (screenWidth * resizeFactor).toInt()
        val targetScreenHeight = (screenHeight * resizeFactor).toInt()

        // If image is already smaller than target screen in both dimensions, keep original size
        if (originalWidth <= targetScreenWidth && originalHeight <= targetScreenHeight) {
            return Pair(originalWidth, originalHeight)
        }

        // Calculate ratios
        val widthRatio = targetScreenWidth.toFloat() / originalWidth
        val heightRatio = targetScreenHeight.toFloat() / originalHeight

        // Use the smaller ratio to ensure image fits screen while maintaining aspect ratio
        val ratio = minOf(widthRatio, heightRatio)

        // Calculate new dimensions (round to integers)
        val newWidth = (originalWidth * ratio).toInt()
        val newHeight = (originalHeight * ratio).toInt()

        return Pair(newWidth, newHeight)
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