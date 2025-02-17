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

    private val mediaItems = mutableListOf<MediaItem>()
    private var currentIndex = -1

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    companion object {
        private const val TAG = "LockScreenPhotoManager"

        @Volatile private var instance: PhotoManager? = null

    }

    fun getPhotoCount(): Int {
        val count = mediaItems.size
        Log.d(TAG, "Getting photo count from lock screen PhotoManager: $count")
        return count
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