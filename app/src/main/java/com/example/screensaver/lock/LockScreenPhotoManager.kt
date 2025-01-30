package com.example.screensaver.lock

import android.content.Context
import android.util.Log
import com.example.screensaver.models.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class LockScreenPhotoManager @Inject constructor(
    private val context: Context
) {
    private val mediaItems = mutableListOf<MediaItem>()
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState
    private val KEY_HAS_PHOTOS = "has_photos"
    private val KEY_MEDIA_ITEMS = "media_items"
    private val preferences = context.getSharedPreferences("photo_manager", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "LockScreenPhotoManager"
    }

    enum class LoadingState {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR
    }

    init {
        // Load cached items on init
        loadCachedItems()
        // Check if we had photos before
        val hasPhotos = preferences.getBoolean(KEY_HAS_PHOTOS, false)
        Log.d(TAG, "Initializing with previous photo state: $hasPhotos")
    }

    private fun saveItems() {
        try {
            val jsonArray = JSONArray()
            mediaItems.forEach { item ->
                val jsonObject = JSONObject().apply {
                    put("id", item.id)
                    put("albumId", item.albumId)
                    put("baseUrl", item.baseUrl)
                    put("mimeType", item.mimeType)
                    put("width", item.width)
                    put("height", item.height)
                    item.description?.let { put("description", it) }
                    put("createdAt", item.createdAt)
                    put("loadState", item.loadState.name)
                }
                jsonArray.put(jsonObject)
            }

            preferences.edit()
                .putString(KEY_MEDIA_ITEMS, jsonArray.toString())
                .putBoolean(KEY_HAS_PHOTOS, mediaItems.isNotEmpty())
                .apply()
            Log.d(TAG, "Saved ${mediaItems.size} items to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving items", e)
        }
    }

    private fun loadCachedItems() {
        try {
            val json = preferences.getString(KEY_MEDIA_ITEMS, null)
            if (!json.isNullOrEmpty()) {
                val jsonArray = JSONArray(json)
                val items = mutableListOf<MediaItem>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    items.add(MediaItem(
                        id = obj.getString("id"),
                        albumId = obj.getString("albumId"),
                        baseUrl = obj.getString("baseUrl"),
                        mimeType = obj.getString("mimeType"),
                        width = obj.getInt("width"),
                        height = obj.getInt("height"),
                        description = if (obj.has("description")) obj.getString("description") else null,
                        createdAt = if (obj.has("createdAt")) obj.getLong("createdAt") else System.currentTimeMillis(),
                        loadState = MediaItem.LoadState.valueOf(
                            if (obj.has("loadState")) obj.getString("loadState") else "IDLE"
                        )
                    ))
                }

                mediaItems.clear()
                mediaItems.addAll(items)
                Log.d(TAG, "Loaded ${items.size} items from cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached items", e)
        }
    }

    suspend fun loadPhotos(): List<MediaItem>? {
        _loadingState.value = LoadingState.LOADING
        return try {
            if (mediaItems.isEmpty()) {
                loadCachedItems() // Try loading from cache if empty
            }
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
        saveItems() // Save to preferences
        Log.d(TAG, "Added ${photos.size} photos, total count: ${mediaItems.size}")
    }

    fun hadPhotos(): Boolean {
        return preferences.getBoolean(KEY_HAS_PHOTOS, false)
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
        preferences.edit()
            .remove(KEY_MEDIA_ITEMS)
            .putBoolean(KEY_HAS_PHOTOS, false)
            .apply()
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