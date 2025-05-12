package com.photostreamr.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.widget.ImageView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import com.photostreamr.ui.BitmapMemoryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoilImageLoadStrategy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bitmapMemoryManager: BitmapMemoryManager
) : ImageLoadStrategy {

    private val TAG = "CoilImageLoadStrategy"

    /**
     * Get appropriate scale factor for bitmap sizing based on API level
     * More aggressive downsampling on older devices to prevent OOM errors
     */
    private fun getApiOptimizedScaleFactor(): Float {
        return when {
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O -> 0.33f  // Android 8.0: Most restrictive (1/3)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> 0.4f   // Android 9.0: Still quite restrictive
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> 0.5f   // Android 10: Moderate restriction
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.S -> 0.75f  // Android 11-12: Minor restriction
            else -> 1.0f  // Android 13+: No restrictions
        }
    }

    val imageLoader: ImageLoader by lazy {
        val memoryCacheSizePercent = when {
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O -> 0.15  // 15% for Android 8.0
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> 0.2   // 20% for Android 9.0
            else -> 0.25  // 25% for Android 10+
        }

        // Configure hardware bitmap usage based on API level
        val allowHardwareBitmaps = when {
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O -> false  // Disable on Android 8.0 (Oreo)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> false  // Disable on Android 9.0 (Pie)
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q -> false  // Disable on Android 10.0 (Q)
            else -> true  // Enable on Android 11+ (R and above)
        }

        Log.i(TAG, "Configuring ImageLoader: " +
                "API ${Build.VERSION.SDK_INT}, " +
                "Memory cache ${(memoryCacheSizePercent * 100).toInt()}%, " +
                "Hardware bitmaps ${if(allowHardwareBitmaps) "enabled" else "disabled"}")

        ImageLoader.Builder(context)
            .allowHardware(allowHardwareBitmaps)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(memoryCacheSizePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override suspend fun loadImage(
        uri: String,
        target: ImageView,
        options: ImageLoadStrategy.ImageLoadOptions
    ): Result<Drawable> = withContext(Dispatchers.IO) {
        try {
            // Calculate dimensions based on API level if none are provided
            var targetWidth = options.width
            var targetHeight = options.height

            if (targetWidth <= 0 || targetHeight <= 0) {
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                val scaleFactor = getApiOptimizedScaleFactor()
                targetWidth = (screenWidth * scaleFactor).toInt()
                targetHeight = (screenHeight * scaleFactor).toInt()

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && scaleFactor < 1.0f) {
                    Log.d(TAG, "Applying downsizing for API ${Build.VERSION.SDK_INT}: " +
                            "${targetWidth}x${targetHeight} (scale: $scaleFactor)")
                }
            }

            val request = ImageRequest.Builder(context)
                .data(uri)
                .target(target)
                .apply {
                    // Apply calculated size for better memory management
                    size(targetWidth, targetHeight)

                    // Apply scale type
                    scale(when(options.scaleType) {
                        ImageLoadStrategy.ScaleType.FIT_CENTER -> Scale.FIT
                        ImageLoadStrategy.ScaleType.CENTER_CROP -> Scale.FILL
                        ImageLoadStrategy.ScaleType.CENTER_INSIDE -> Scale.FIT
                    })

                    // Apply cache policy
                    diskCachePolicy(when(options.diskCacheStrategy) {
                        ImageLoadStrategy.DiskCacheStrategy.NONE -> CachePolicy.DISABLED
                        ImageLoadStrategy.DiskCacheStrategy.AUTOMATIC,
                        ImageLoadStrategy.DiskCacheStrategy.ALL -> CachePolicy.ENABLED
                    })

                    // Apply placeholder and error drawables
                    options.placeholder?.let { placeholder(it) }
                    options.error?.let { error(it) }

                    // Set priority
                    if (options.isHighPriority) {
                        listener(onSuccess = { request, result ->
                            val drawable = result.drawable
                            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                                val key = "loaded:${uri.hashCode()}"
                                bitmapMemoryManager.registerActiveBitmap(key, drawable.bitmap)
                            }
                        })
                    }
                }
                .build()

            when (val result = imageLoader.execute(request)) {
                is SuccessResult -> {
                    val drawable = result.drawable
                    if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                        val key = "loaded:${uri.hashCode()}"
                        bitmapMemoryManager.registerActiveBitmap(key, drawable.bitmap)
                    }
                    Result.success(drawable)
                }
                is ErrorResult -> {
                    Result.failure(result.throwable)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image: $uri", e)
            Result.failure(e)
        }
    }

    override suspend fun preloadImage(uri: String, options: ImageLoadStrategy.ImageLoadOptions): Result<Drawable> =
        withContext(Dispatchers.IO) {
            try {
                // Calculate dimensions based on API level if none are provided
                var targetWidth = options.width
                var targetHeight = options.height

                if (targetWidth <= 0 || targetHeight <= 0) {
                    val displayMetrics = context.resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels

                    val scaleFactor = getApiOptimizedScaleFactor()
                    targetWidth = (screenWidth * scaleFactor).toInt()
                    targetHeight = (screenHeight * scaleFactor).toInt()
                }

                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .apply {
                        // Apply calculated size
                        size(targetWidth, targetHeight)

                        diskCachePolicy(when(options.diskCacheStrategy) {
                            ImageLoadStrategy.DiskCacheStrategy.NONE -> CachePolicy.DISABLED
                            else -> CachePolicy.ENABLED
                        })

                        if (options.isHighPriority) {
                            memoryCachePolicy(CachePolicy.ENABLED)
                        }
                    }
                    .build()

                when (val result = imageLoader.execute(request)) {
                    is SuccessResult -> {
                        val drawable = result.drawable
                        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                            val key = "preload:${uri.hashCode()}"
                            bitmapMemoryManager.registerActiveBitmap(key, drawable.bitmap)
                        }
                        Result.success(drawable)
                    }
                    is ErrorResult -> {
                        Result.failure(result.throwable)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading image: $uri", e)
                Result.failure(e)
            }
        }

    override fun clearMemoryCache() {
        imageLoader.memoryCache?.clear()
    }

    override fun clearDiskCache() {
        imageLoader.diskCache?.clear()
    }

    /**
     * Load a bitmap synchronously - used for template creation workflows
     */
    suspend fun loadBitmap(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // Apply API-specific optimizations for bitmap loading
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val scaleFactor = getApiOptimizedScaleFactor()
            val targetWidth = (screenWidth * scaleFactor).toInt()
            val targetHeight = (screenHeight * scaleFactor).toInt()

            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false) // Disable hardware bitmaps to allow bitmap manipulation
                .size(targetWidth, targetHeight) // Apply API-specific sizing
                .build()

            val result = imageLoader.execute(request)
            if (result is SuccessResult && result.drawable is BitmapDrawable) {
                return@withContext (result.drawable as BitmapDrawable).bitmap
            } else {
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: $uri", e)
            return@withContext null
        }
    }
}