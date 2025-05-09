package com.photostreamr.photos

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoUriManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val persistentPhotoCache: PersistentPhotoCache
) {
    companion object {
        private const val TAG = "PhotoUriManager"

        // Cache parameters
        private const val CACHE_SIZE = 1000 // Max number of entries to cache
        private const val CACHE_EXPIRY_MS = 300000L // 5 minutes
    }

    // Thread-safe cache using ConcurrentHashMap
    private val validationCache = ConcurrentHashMap<String, Boolean>(CACHE_SIZE)
    private val timestampCache = ConcurrentHashMap<String, Long>(CACHE_SIZE)

    // Stats counters
    private var cacheHits = 0
    private var cacheMisses = 0
    private var totalValidations = 0

    // Log stats periodically (every 100 validations)
    private fun logCacheStatsIfNeeded() {
        if (++totalValidations % 100 == 0) {
            val hitRate = if (totalValidations > 0)
                (cacheHits.toFloat() / totalValidations * 100).toInt()
            else 0

            Log.d(TAG, "URI validation cache stats: $cacheHits hits, $cacheMisses misses, " +
                    "$hitRate% hit rate, ${validationCache.size} entries")
        }
    }

    // Check if a cache entry is expired
    private fun isEntryExpired(uri: String): Boolean {
        val timestamp = timestampCache[uri] ?: return true
        return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS
    }

    fun hasValidPermission(uri: Uri): Boolean {
        val uriString = uri.toString()

        // Fast path: Check cache first
        val cachedResult = validationCache[uriString]
        if (cachedResult != null && !isEntryExpired(uriString)) {
            cacheHits++
            logCacheStatsIfNeeded()
            return cachedResult
        }

        // Cache miss - perform actual validation
        cacheMisses++

        // For Google Photos URIs - check only cache
        if (isGooglePhotosUri(uri)) {
            val isCached = persistentPhotoCache.isUriCached(uriString)
            Log.d(TAG, "Google Photos URI $uriString - cached: $isCached")

            // Store in cache
            validationCache[uriString] = isCached
            timestampCache[uriString] = System.currentTimeMillis()

            logCacheStatsIfNeeded()
            return isCached
        }
        // For local files - check if file exists
        else if (uri.scheme == "content" || uri.scheme == "file") {
            try {
                // For content URIs, try to open the file
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    Log.d(TAG, "Local file URI $uriString - exists: true")

                    // Store in cache
                    validationCache[uriString] = true
                    timestampCache[uriString] = System.currentTimeMillis()

                    logCacheStatsIfNeeded()
                    return true
                }

                // If we got here, the stream couldn't be opened
                Log.d(TAG, "Local file URI $uriString - stream could not be opened")

                // Store in cache
                validationCache[uriString] = false
                timestampCache[uriString] = System.currentTimeMillis()

                logCacheStatsIfNeeded()
                return false

            } catch (e: Exception) {
                Log.d(TAG, "Local file URI $uriString - exists: false (${e.message})")

                // Store in cache
                validationCache[uriString] = false
                timestampCache[uriString] = System.currentTimeMillis()

                logCacheStatsIfNeeded()
                return false
            }
        }

        Log.d(TAG, "Unknown URI scheme ${uri.scheme} for $uriString")

        // Store in cache
        validationCache[uriString] = false
        timestampCache[uriString] = System.currentTimeMillis()

        logCacheStatsIfNeeded()
        return false
    }

    // Clean up expired cache entries
    fun cleanExpiredEntries() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = timestampCache.entries
            .filter { currentTime - it.value > CACHE_EXPIRY_MS }
            .map { it.key }

        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Cleaning ${expiredKeys.size} expired cache entries")
            expiredKeys.forEach { key ->
                validationCache.remove(key)
                timestampCache.remove(key)
            }
        }
    }

    // Add method to clear the cache
    fun clearValidationCache() {
        validationCache.clear()
        timestampCache.clear()
        cacheHits = 0
        cacheMisses = 0
        totalValidations = 0
        Log.d(TAG, "URI validation cache cleared")
    }

    fun isGooglePhotosUri(uri: Uri): Boolean {
        val uriString = uri.toString()
        return uriString.contains("com.google.android.apps.photos") ||
                uriString.contains("googleusercontent.com")
    }

    data class UriData(
        val uri: String,
        val type: String,
        val hasPersistedPermission: Boolean,
        val timestamp: Long
    )
}