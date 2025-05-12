package com.photostreamr.photos

import android.graphics.drawable.Drawable
import android.widget.ImageView
import kotlinx.coroutines.flow.Flow

interface ImageLoadStrategy {
    suspend fun loadImage(
        uri: String,
        target: ImageView,
        options: ImageLoadOptions
    ): Result<Drawable>

    suspend fun preloadImage(uri: String, options: ImageLoadOptions): Result<Drawable>

    fun clearMemoryCache()
    fun clearDiskCache()

    data class ImageLoadOptions(
        val width: Int = 0,
        val height: Int = 0,
        val scaleType: ScaleType = ScaleType.FIT_CENTER,
        val diskCacheStrategy: DiskCacheStrategy = DiskCacheStrategy.AUTOMATIC,
        val placeholder: Int? = null,
        val error: Int? = null,
        val isHighPriority: Boolean = false
    )

    enum class ScaleType { FIT_CENTER, CENTER_CROP, CENTER_INSIDE }
    enum class DiskCacheStrategy { NONE, AUTOMATIC, ALL }
}