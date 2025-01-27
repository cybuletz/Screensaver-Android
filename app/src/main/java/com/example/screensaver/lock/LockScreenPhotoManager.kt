package com.example.screensaver.lock

import android.content.Context
import android.util.Log
import com.example.screensaver.models.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LockScreenPhotoManager @Inject constructor(
    private val context: Context
) {
    private val mediaItems = mutableListOf<MediaItem>()
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    companion object {
        private const val TAG = "LockScreenPhotoManager"
    }

    enum class LoadingState {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR
    }

    suspend fun loadPhotos(): List<MediaItem>? {
        _loadingState.value = LoadingState.LOADING
        return try {
            val photos = mediaItems.toList()
            _loadingState.value = LoadingState.SUCCESS
            Log.d(TAG, "Loaded ${photos.size} photos")
            photos
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos", e)
            _loadingState.value = LoadingState.ERROR
            null
        }
    }

    fun addPhotos(photos: List<MediaItem>) {
        mediaItems.clear()
        mediaItems.addAll(photos)
        Log.d(TAG, "Added ${photos.size} photos, total count: ${mediaItems.size}")
    }

    fun getPhotoCount(): Int {
        val count = mediaItems.size
        Log.d(TAG, "Getting photo count from lock screen PhotoManager: $count")
        return count
    }

    fun getPhotoUrl(index: Int): String? {
        return if (index in mediaItems.indices) {
            val url = mediaItems[index].baseUrl
            Log.d(TAG, "Getting photo URL for index $index: $url")
            url
        } else {
            Log.e(TAG, "Invalid photo index: $index, total photos: ${mediaItems.size}")
            null
        }
    }

    fun clearPhotos() {
        mediaItems.clear()
        Log.d(TAG, "Cleared all photos")
    }

    fun getAllPhotos(): List<MediaItem> = mediaItems.toList()

    fun cleanup() {
        clearPhotos()
        _loadingState.value = LoadingState.IDLE
        Log.d(TAG, "Manager cleaned up")
    }

    fun hasValidPhotos(): Boolean {
        return mediaItems.isNotEmpty()
    }
}