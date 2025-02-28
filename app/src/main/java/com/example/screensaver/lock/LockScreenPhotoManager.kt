package com.example.screensaver.lock

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.screensaver.models.MediaItem
import com.example.screensaver.models.AlbumInfo
import com.example.screensaver.photos.VirtualAlbum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import com.example.screensaver.shared.GooglePhotosManager
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class LockScreenPhotoManager @Inject constructor(
    private val context: Context,
    private val googlePhotosManager: GooglePhotosManager
    ) {
    private val mediaItems = mutableListOf<MediaItem>()
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState
    private val KEY_HAS_PHOTOS = "has_photos"
    private val KEY_MEDIA_ITEMS = "media_items"
    private val preferences = context.getSharedPreferences("photo_manager", Context.MODE_PRIVATE)

    private val virtualAlbums = mutableListOf<VirtualAlbum>()
    private val KEY_VIRTUAL_ALBUMS = "virtual_albums"

    suspend fun refreshTokens(): Boolean {
        return googlePhotosManager.refreshTokens()
    }

    companion object {
        private const val TAG = "LockScreenPhotoManager"
    }

    enum class LoadingState {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR
    }

    enum class PhotoAddMode {
        APPEND,    // Add to existing photos
        REPLACE,   // Clear existing and add new
        MERGE      // Add new but prevent duplicates
    }

    data class VirtualAlbum(
        val id: String,
        val name: String,
        val photoUris: List<String>,
        val dateCreated: Long = System.currentTimeMillis(),
        val isSelected: Boolean = false  // Added this property
    )

    init {
        loadCachedItems()
        loadVirtualAlbums()
        val hasPhotos = preferences.getBoolean(KEY_HAS_PHOTOS, false)
        Log.d(TAG, "Initializing with previous photo state: $hasPhotos")
    }

    fun getLocalAlbums(): List<AlbumInfo> {
        val albums = mutableListOf<AlbumInfo>()

        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media._ID
        )

        val selection = "${MediaStore.Images.Media.SIZE} > 0"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val bucketIdColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

                val processedBuckets = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val bucketId = it.getString(bucketIdColumn)
                    if (!processedBuckets.contains(bucketId)) {
                        processedBuckets.add(bucketId)

                        val bucketName = it.getString(bucketNameColumn) ?: "Unknown Album"
                        val photoId = it.getLong(idColumn)

                        val coverPhotoUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            photoId
                        )

                        val photoCount = getPhotoCountForAlbum(bucketId)

                        albums.add(AlbumInfo(
                            id = bucketId.toLong(),
                            name = bucketName,
                            photosCount = photoCount,
                            coverPhotoUri = coverPhotoUri
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading albums", e)
        }

        return albums
    }

    private fun getPhotoCountForAlbum(bucketId: String): Int {
        var count = 0
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null,
                selection,
                selectionArgs,
                null
            )

            count = cursor?.count ?: 0
            cursor?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error counting photos in album", e)
        }

        return count
    }

    fun getPhotosFromAlbum(albumId: String): List<MediaItem> {
        val photos = mutableListOf<MediaItem>()
        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(albumId)

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT
                ),
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val widthColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    photos.add(MediaItem(
                        id = id.toString(),
                        albumId = albumId,
                        baseUrl = contentUri.toString(),
                        mimeType = it.getString(mimeColumn),
                        width = it.getInt(widthColumn),
                        height = it.getInt(heightColumn),
                        description = it.getString(nameColumn),
                        createdAt = it.getLong(dateColumn) * 1000, // Convert to milliseconds
                        loadState = MediaItem.LoadState.IDLE
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos from album", e)
        }

        return photos
    }

    suspend fun loadPhotos(): List<MediaItem>? {
        _loadingState.value = LoadingState.LOADING
        return try {
            if (mediaItems.isEmpty()) {
                loadCachedItems()
                loadVirtualAlbums()
            }

            val allPhotos = mutableListOf<MediaItem>()

            // Add regular photos
            allPhotos.addAll(mediaItems)

            // Add virtual album photos
            virtualAlbums.forEach { album ->
                album.photoUris.forEach { uri ->
                    allPhotos.add(MediaItem(
                        id = "${album.id}_${uri.hashCode()}",
                        albumId = album.id,
                        baseUrl = uri,
                        mimeType = "image/*",
                        width = 0,
                        height = 0,
                        description = "From album: ${album.name}",
                        createdAt = album.dateCreated,
                        loadState = MediaItem.LoadState.IDLE
                    ))
                }
            }

            _loadingState.value = LoadingState.SUCCESS
            allPhotos
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos", e)
            _loadingState.value = LoadingState.ERROR
            null
        }
    }

    fun toggleVirtualAlbumSelection(albumId: String) {
        val album = virtualAlbums.find { it.id == albumId } ?: return
        val updatedAlbum = album.copy(isSelected = !album.isSelected)

        virtualAlbums.removeIf { it.id == albumId }
        virtualAlbums.add(updatedAlbum)
        saveVirtualAlbums()

        Log.d(TAG, "Toggled selection for album ${album.name} (${album.id})")
    }

    suspend fun validateStoredPhotos() {
        try {
            val currentPhotos = loadPhotos() ?: emptyList()

            // Check each photo URI and remove invalid ones
            val validPhotos = currentPhotos.filter { photo ->
                try {
                    val uri = Uri.parse(photo.baseUrl)
                    if (uri.toString().startsWith("content://media/picker/")) {
                        // For picker URIs, check if we have permission
                        context.contentResolver.persistedUriPermissions.any {
                            it.uri == uri && it.isReadPermission
                        }
                    } else {
                        // Not a picker URI, consider valid
                        true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error validating photo URI: ${photo.baseUrl}", e)
                    false
                }
            }

            // Update storage if needed
            if (validPhotos.size < currentPhotos.size) {
                Log.d(TAG, "Removing ${currentPhotos.size - validPhotos.size} invalid URIs")
                clearPhotos()
                if (validPhotos.isNotEmpty()) {
                    addPhotos(validPhotos)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating photos", e)
        }
    }

    fun addPhotoUrls(urls: List<String>) {
        val items = urls.mapIndexed { index, url ->
            MediaItem(
                id = index.toString(),
                albumId = "local",
                baseUrl = url,
                mimeType = "image/*",
                width = 0,
                height = 0,
                description = null,
                createdAt = System.currentTimeMillis(),
                loadState = MediaItem.LoadState.IDLE
            )
        }
        addPhotos(items)
        Log.d(TAG, "Added ${items.size} photo URLs as MediaItems")
    }

    fun getPhotoUrl(index: Int): String? {
        return if (index in mediaItems.indices) {
            val url = mediaItems[index].baseUrl
            Log.d(TAG, "Getting photo URL for index $index: $url")
            if (url.contains("googleusercontent.com") && !url.contains("=w")) {
                "$url=w2048-h1024"
            } else {
                url
            }
        } else {
            Log.e(TAG, "Invalid photo index: $index, total photos: ${mediaItems.size}")
            null
        }
    }

    fun getPhotoCount(): Int {
        val count = mediaItems.size
        Log.d(TAG, "Current photo count: $count")
        return count
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
            Log.d(TAG, "Successfully saved ${mediaItems.size} items to cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving items to cache", e)
        }
    }

    fun removePhoto(uri: String) {
        val previousCount = mediaItems.size
        mediaItems.removeIf { it.baseUrl == uri }

        // Also remove from virtual albums if present
        virtualAlbums.forEach { album ->
            val updatedPhotoUris = album.photoUris.filterNot { it == uri }
            if (updatedPhotoUris.size != album.photoUris.size) {
                val updatedAlbum = album.copy(photoUris = updatedPhotoUris)
                virtualAlbums.removeIf { it.id == album.id }
                if (updatedPhotoUris.isNotEmpty()) {
                    virtualAlbums.add(updatedAlbum)
                }
            }
        }

        // Remove empty virtual albums
        virtualAlbums.removeIf { it.photoUris.isEmpty() }

        // Save changes
        saveItems()
        saveVirtualAlbums()

        Log.d(TAG, "Removed photo with URI: $uri (previous count: $previousCount, new count: ${mediaItems.size})")
    }

    private fun saveVirtualAlbums() {
        try {
            val jsonArray = JSONArray()
            virtualAlbums.forEach { album ->
                val jsonObject = JSONObject().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("photoUris", JSONArray(album.photoUris))
                    put("dateCreated", album.dateCreated)
                }
                jsonArray.put(jsonObject)
            }
            preferences.edit()
                .putString(KEY_VIRTUAL_ALBUMS, jsonArray.toString())
                .apply()
            Log.d(TAG, "Successfully saved ${virtualAlbums.size} virtual albums")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving virtual albums", e)
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
                Log.d(TAG, "Successfully loaded ${items.size} items from cache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading cached items", e)
        }
    }

    private fun loadVirtualAlbums() {
        try {
            val json = preferences.getString(KEY_VIRTUAL_ALBUMS, null)
            if (!json.isNullOrEmpty()) {
                val jsonArray = JSONArray(json)
                virtualAlbums.clear()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val urisArray = obj.getJSONArray("photoUris")
                    val photoUris = mutableListOf<String>()
                    for (j in 0 until urisArray.length()) {
                        photoUris.add(urisArray.getString(j))
                    }

                    virtualAlbums.add(
                        VirtualAlbum(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            photoUris = photoUris,
                            dateCreated = obj.getLong("dateCreated")
                        )
                    )
                }
                Log.d(TAG, "Successfully loaded ${virtualAlbums.size} virtual albums")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading virtual albums", e)
        }
    }

    fun addVirtualAlbum(album: VirtualAlbum) {
        virtualAlbums.add(album)
        saveVirtualAlbums()
    }

    fun removeVirtualAlbum(albumId: String) {
        virtualAlbums.removeIf { it.id == albumId }
        saveVirtualAlbums()
    }

    fun getVirtualAlbums(): List<VirtualAlbum> = virtualAlbums.toList()

    fun cleanup() {
        val previousCount = mediaItems.size
        mediaItems.clear()
        virtualAlbums.clear()
        preferences.edit()
            .remove(KEY_MEDIA_ITEMS)
            .remove(KEY_VIRTUAL_ALBUMS)
            .putBoolean(KEY_HAS_PHOTOS, false)
            .apply()
        Log.d(TAG, "Explicitly cleared all photos and virtual albums (previous count: $previousCount)")
    }

    fun addPhotos(photos: List<MediaItem>, mode: PhotoAddMode = PhotoAddMode.MERGE) {
        Log.d(TAG, """Adding photos:
            • Mode: $mode
            • Current count: ${mediaItems.size}
            • New photos: ${photos.size}""".trimMargin())

        when (mode) {
            PhotoAddMode.REPLACE -> {
                val previousCount = mediaItems.size
                mediaItems.clear()
                mediaItems.addAll(photos)
                Log.d(TAG, "Replaced $previousCount photos with ${photos.size} new photos")
            }
            PhotoAddMode.APPEND -> {
                val previousCount = mediaItems.size
                mediaItems.addAll(photos)
                Log.d(TAG, "Appended ${photos.size} photos to existing $previousCount photos")
            }
            PhotoAddMode.MERGE -> {
                val previousCount = mediaItems.size
                val newPhotos = photos.filterNot { newPhoto -> isDuplicate(newPhoto) }
                mediaItems.addAll(newPhotos)
                Log.d(TAG, """Merged photos:
                    • Previous count: $previousCount
                    • New unique photos: ${newPhotos.size}
                    • Duplicates filtered: ${photos.size - newPhotos.size}
                    • Total now: ${mediaItems.size}""".trimMargin())
            }
        }

        saveItems()
        Log.d(TAG, "Final photo count: ${mediaItems.size}")
    }

    fun hasPhoto(uri: String): Boolean {
        return mediaItems.any { it.baseUrl == uri }
    }

    fun addPhoto(uri: String) {
        if (!hasPhoto(uri)) {
            val newItem = MediaItem(
                id = uri,
                albumId = "local_picked",
                baseUrl = uri,
                mimeType = "image/*",
                width = 0,
                height = 0,
                description = null,
                createdAt = System.currentTimeMillis(),
                loadState = MediaItem.LoadState.IDLE
            )
            addPhotos(listOf(newItem), PhotoAddMode.MERGE)
            Log.d(TAG, "Added new photo: $uri")
        } else {
            Log.d(TAG, "Photo already exists: $uri")
        }
    }

    private fun isDuplicate(newItem: MediaItem): Boolean {
        return mediaItems.any { existing ->
            existing.id == newItem.id || existing.baseUrl == newItem.baseUrl
        }
    }

    fun clearPhotos() {
        val previousCount = mediaItems.size
        mediaItems.clear()
        preferences.edit()
            .remove(KEY_MEDIA_ITEMS)
            .putBoolean(KEY_HAS_PHOTOS, false)
            .apply()
        Log.d(TAG, "Explicitly cleared all photos (previous count: $previousCount)")
    }
}