package com.photostreamr.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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

    private val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
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
            val request = ImageRequest.Builder(context)
                .data(uri)
                .target(target)
                .apply {
                    if (options.width > 0 && options.height > 0) {
                        size(options.width, options.height)
                    }

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
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .apply {
                        if (options.width > 0 && options.height > 0) {
                            size(options.width, options.height)
                        }

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
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false) // Disable hardware bitmaps to allow bitmap manipulation
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