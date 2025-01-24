package com.example.screensaver.utils

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.example.screensaver.R
import com.example.screensaver.models.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

@Singleton
class PhotoLoadingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scope: CoroutineScope
) {

    private val glideRequestManager: RequestManager = Glide.with(context)
    private lateinit var diskCache: File
    private val loadingJobs = mutableMapOf<String, Job>()
    private var currentLoadingItem: MediaItem? = null

    companion object {
        private const val CACHE_SIZE_PERCENTAGE = 0.25 // Use 25% of available memory
        private const val DISK_CACHE_SIZE = 250L * 1024 * 1024 // 250MB
        private const val CORNER_RADIUS = 8f
        private const val CROSSFADE_DURATION = 300

        const val QUALITY_LOW = 1
        const val QUALITY_MEDIUM = 2
        const val QUALITY_HIGH = 3
        private const val TAG = "PhotoLoadingManager"
    }

    init {
        // Move disk operations to a background thread
        scope.launch(Dispatchers.IO) {
            diskCache = File(context.cacheDir, "photo_cache").apply {
                if (!exists()) mkdirs()
            }
        }
    }

    fun loadPhoto(mediaItem: MediaItem, imageView: ImageView) {
        try {
            if (mediaItem.baseUrl.startsWith("http")) {
                Glide.with(imageView.context)
                    .load(mediaItem.baseUrl)
                    .apply(getRequestOptions())
                    .into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_error)
            }
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_error)
        }
    }

    data class PhotoData(
        val id: String,
        val baseUrl: String,
        val mimeType: String,
        val filename: String? = null
    )

    suspend fun getAlbumPhotos(albumId: String): List<PhotoData> {
        return try {
            // Implement your actual photo loading logic here
            // This is just a placeholder implementation
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading album photos: $albumId", e)
            emptyList()
        }
    }

    private fun createRequestListener(
        mediaItem: MediaItem,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ): RequestListener<Drawable> = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            mediaItem.updateLoadState(MediaItem.LoadState.ERROR)
            onError()
            return false
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            mediaItem.updateLoadState(MediaItem.LoadState.LOADED)
            onSuccess()
            return false
        }
    }

    private fun getRequestOptions(): RequestOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .centerCrop()
        .transform(com.bumptech.glide.load.resource.bitmap.RoundedCorners(CORNER_RADIUS.toInt()))
        .placeholder(R.drawable.ic_photo_placeholder)
        .error(R.drawable.ic_error)

    suspend fun preloadPhoto(mediaItem: MediaItem) {
        try {
            withContext(Dispatchers.IO) {
                Glide.with(context)
                    .load(mediaItem.baseUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading photo: ${mediaItem.id}", e)
        }
    }

    private fun cancelLoadingForView(imageView: ImageView) {
        glideRequestManager.clear(imageView)
    }

    fun cancelLoading(mediaItem: MediaItem) {
        loadingJobs[mediaItem.id]?.cancel()
        loadingJobs.remove(mediaItem.id)
        if (currentLoadingItem == mediaItem) {
            currentLoadingItem = null
        }
    }

    suspend fun clearDiskCache() {
        withContext(Dispatchers.IO) {
            Glide.get(context).clearDiskCache()
        }
    }

    fun isPhotoCached(mediaItem: MediaItem): Boolean {
        return try {
            Glide.with(context)
                .load(mediaItem.baseUrl)
                .onlyRetrieveFromCache(true)
                .submit()
                .get() != null
        } catch (e: Exception) {
            false
        }
    }

    fun cleanup() {
        scope.launch(Dispatchers.IO) {
            try {
                Glide.get(context).clearDiskCache()
                withContext(Dispatchers.Main) {
                    Glide.get(context).clearMemory()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    fun clearMemory() {
        Glide.get(context).clearMemory()
    }

    fun getCacheSize(): Long {
        return diskCache.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    data class Config(
        val maxMemoryCacheSize: Int = (Runtime.getRuntime().maxMemory() * CACHE_SIZE_PERCENTAGE).toInt(),
        val maxDiskCacheSize: Long = DISK_CACHE_SIZE,
        val cornerRadius: Float = CORNER_RADIUS,
        val crossfadeDuration: Int = CROSSFADE_DURATION
    )
}