package com.example.screensaver.lock

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.models.MediaItem
import com.example.screensaver.utils.PhotoLoadingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoManager @Inject constructor(
    private val context: Context,
    private val photoLoadingManager: PhotoLoadingManager
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    private val mediaItems = mutableListOf<MediaItem>()
    private var currentIndex = -1

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    companion object {
        private const val TAG = "LockScreenPhotoManager"
        private const val PRELOAD_AHEAD_COUNT = 2

        @Volatile private var instance: PhotoManager? = null

        fun getInstance(context: Context): PhotoManager {
            return instance ?: synchronized(this) {
                instance ?: PhotoManager(
                    context.applicationContext,
                    PhotoLoadingManager(context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.Main))
                ).also { instance = it }
            }
        }
    }

    suspend fun loadPhotos(): Boolean = withContext(Dispatchers.IO) {
        try {
            _loadingState.value = LoadingState.LOADING

            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val selectedAlbums = preferences.getStringSet("selected_albums", emptySet()) ?: emptySet()

            if (selectedAlbums.isEmpty()) {
                Log.w(TAG, "No albums selected")
                _loadingState.value = LoadingState.ERROR
                return@withContext false
            }

            mediaItems.clear()
            selectedAlbums.forEach { albumId ->
                val items = loadAlbumPhotos(albumId)
                mediaItems.addAll(items)
            }

            if (mediaItems.isEmpty()) {
                Log.w(TAG, "No photos found in selected albums")
                _loadingState.value = LoadingState.ERROR
                return@withContext false
            }

            mediaItems.shuffle()
            _loadingState.value = LoadingState.SUCCESS
            preloadInitialPhotos()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos", e)
            _loadingState.value = LoadingState.ERROR
            false
        }
    }

    private suspend fun loadAlbumPhotos(albumId: String): List<MediaItem> {
        return try {
            photoLoadingManager.getAlbumPhotos(albumId).map { photoData ->
                MediaItem(
                    id = photoData.id,
                    albumId = albumId,
                    baseUrl = photoData.baseUrl,
                    mimeType = photoData.mimeType,
                    width = 1920,
                    height = 1080,
                    description = photoData.filename ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading album photos: $albumId", e)
            emptyList()
        }
    }

    fun getPhotoCount(): Int = mediaItems.size

    fun getPhotoUrl(index: Int): String? {
        return if (index in mediaItems.indices) {
            currentIndex = index
            preloadNextPhotos(index)
            mediaItems[index].baseUrl
        } else {
            null
        }
    }

    private fun preloadInitialPhotos() {
        coroutineScope.launch {
            for (i in 0 until minOf(PRELOAD_AHEAD_COUNT, mediaItems.size)) {
                preloadPhoto(i)
            }
        }
    }

    private fun preloadNextPhotos(currentIndex: Int) {
        coroutineScope.launch {
            for (i in 1..PRELOAD_AHEAD_COUNT) {
                val nextIndex = (currentIndex + i) % mediaItems.size
                preloadPhoto(nextIndex)
            }
        }
    }

    suspend fun preloadNextPhoto(index: Int) {
        preloadPhoto(index)
    }

    private suspend fun preloadPhoto(index: Int) {
        if (index !in mediaItems.indices) return

        try {
            withContext(Dispatchers.IO) {
                val mediaItem = mediaItems[index]
                if (!photoLoadingManager.isPhotoCached(mediaItem)) {
                    photoLoadingManager.preloadPhoto(mediaItem)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preloading photo at index $index", e)
        }
    }

    fun cleanup() {
        mediaItems.clear()
        currentIndex = -1
        _loadingState.value = LoadingState.IDLE
        photoLoadingManager.cleanup()
        coroutineScope.launch(Dispatchers.IO) {
            Glide.get(context).clearDiskCache()
        }
        Glide.get(context).clearMemory()
    }
}