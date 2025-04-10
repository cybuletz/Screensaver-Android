package com.photostreamr

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.photostreamr.auth.GoogleAuthManager
import com.photostreamr.models.MediaItem
import com.photostreamr.models.AlbumInfo
import com.photostreamr.photos.PersistentPhotoCache
import com.photostreamr.photos.PhotoSourceType
import com.photostreamr.photos.VirtualAlbum
import com.photostreamr.photos.PhotoUriManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.*

@Singleton
class PhotoRepository @Inject constructor(
    private val context: Context,
    private val googleAuthManager: GoogleAuthManager,
    private val photoUriManager: PhotoUriManager
) {
    val persistentPhotoCache: PersistentPhotoCache
        get() = photoUriManager.persistentPhotoCache

    private val mediaItems = mutableListOf<MediaItem>()
    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState
    private val KEY_HAS_PHOTOS = "has_photos"
    private val KEY_MEDIA_ITEMS = "media_items"
    private val preferences = context.getSharedPreferences("photo_manager", Context.MODE_PRIVATE)

    private val virtualAlbums = mutableListOf<VirtualAlbum>()
    private val KEY_VIRTUAL_ALBUMS = "virtual_albums"

    private var repoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    suspend fun refreshTokens(): Boolean {
        return googleAuthManager.refreshTokens()
    }

    companion object {
        private const val TAG = "PhotoRepository"
        private const val KEY_SELECTED_ALBUM_IDS = "selected_album_ids"
    }

    enum class LoadingState {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR
    }

    enum class PhotoAddMode {
        APPEND,
        REPLACE,
        MERGE
    }

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

    fun getPhotoCountBySource(sourceType: PhotoSourceType): Int {
        return allPhotos.filter { mediaItem ->
            when (sourceType) {
                PhotoSourceType.LOCAL -> mediaItem.id.startsWith("content://media")
                PhotoSourceType.GOOGLE_PHOTOS ->
                    mediaItem.id.contains("com.google.android.apps.photos") ||
                            mediaItem.id.contains("googleusercontent.com")
                PhotoSourceType.NETWORK -> mediaItem.id.startsWith("file:///data/data") &&
                        mediaItem.albumId.startsWith("network_")
                PhotoSourceType.VIRTUAL -> mediaItem.albumId.startsWith("virtual_")
            }
        }.size
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

    suspend fun migrateGooglePhotosUri(uri: Uri): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                // First check if we already have a migrated version
                val existingFile = getMigratedFile(uri)
                if (existingFile?.exists() == true) {
                    Log.d(TAG, "Found existing migrated file for: $uri")
                    return@withContext Uri.fromFile(existingFile)
                }

                // If not, try to migrate it
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val fileName = "migrated_${System.currentTimeMillis()}_${uri.lastPathSegment}"
                    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)

                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }

                    Log.d(TAG, "Successfully migrated Google Photos URI to: ${file.absolutePath}")
                    return@withContext Uri.fromFile(file)
                }

                Log.e(TAG, "Failed to open input stream for URI: $uri")
                null
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception migrating URI: $uri", e)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error migrating URI: $uri", e)
                null
            }
        }
    }

    private fun getMigratedFile(uri: Uri): File? {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val files = baseDir?.listFiles { file ->
            file.name.startsWith("migrated_") && file.name.endsWith(uri.lastPathSegment ?: "")
        }
        return files?.firstOrNull()
    }

    fun getAllPhotos(): List<MediaItem> {
        return mediaItems.toList()
    }

    fun loadPhotos(): List<MediaItem>? {
        _loadingState.value = LoadingState.LOADING
        return try {
            loadVirtualAlbums() // Force reload latest state

            val selectedAlbums = virtualAlbums.filter { it.isSelected }
            //Log.d(TAG, """Loading photos from virtual albums:
            //• Total albums: ${virtualAlbums.size}
            //• Selected albums: ${selectedAlbums.size}
            //• Selection states: ${virtualAlbums.map { "${it.id}: ${it.isSelected}" }}""".trimIndent())

            if (selectedAlbums.isEmpty()) {
                Log.d(TAG, "No virtual albums selected, returning null")
                _loadingState.value = LoadingState.SUCCESS
                return null
            }

            val displayPhotos = mutableSetOf<MediaItem>()
            selectedAlbums.forEach { album ->
                album.photoUris.forEach { uri ->
                    displayPhotos.add(MediaItem(
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
            //Log.d(TAG, "Total photos loaded for display: ${displayPhotos.size}")
            displayPhotos.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos", e)
            _loadingState.value = LoadingState.ERROR
            null
        }
    }

    fun validateStoredPhotos() {
        repoScope.launch {
            try {
                val currentPhotos = loadPhotos() ?: emptyList()
                Log.d(TAG, "Validating ${currentPhotos.size} stored photos")

                val validPhotos = currentPhotos.filter { photo ->
                    val uri = Uri.parse(photo.baseUrl)
                    photoUriManager.hasValidPermission(uri)
                }

                // Update storage if any photos were invalid
                if (validPhotos.size < currentPhotos.size) {
                    Log.d(TAG, "Found ${currentPhotos.size - validPhotos.size} invalid URIs")
                    clearPhotos()
                    if (validPhotos.isNotEmpty()) {
                        addPhotos(validPhotos)
                    }
                    Log.d(TAG, "Updated repository with ${validPhotos.size} valid photos")
                } else {
                    Log.d(TAG, "All stored photos are valid")
                }

                // Update virtual albums to remove invalid photos
                val updatedAlbums = virtualAlbums.map { album ->
                    val validUris = album.photoUris.filter { uri ->
                        photoUriManager.hasValidPermission(Uri.parse(uri))
                    }
                    album.copy(photoUris = validUris)
                }.filter { it.photoUris.isNotEmpty() }

                // Update albums if any changes
                if (updatedAlbums.sumOf { it.photoUris.size } < virtualAlbums.sumOf { it.photoUris.size }) {
                    virtualAlbums.clear()
                    virtualAlbums.addAll(updatedAlbums)
                    saveVirtualAlbums()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error validating photos", e)
            }
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

    fun getPhotoCount(): Int {
        val selectedPhotos = loadPhotos() ?: emptyList()
        val count = selectedPhotos.size
        //Log.d(TAG, "Current display photo count: $count")
        return count
    }

    fun getPhotoUrl(index: Int): String? {
        val selectedPhotos = loadPhotos() ?: emptyList()
        return if (index in selectedPhotos.indices) {
            val url = selectedPhotos[index].baseUrl
            //Log.d(TAG, "Getting photo URL for index $index: $url")
            url
        } else {
            Log.e(TAG, "Invalid photo index: $index")
            null
        }
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

        // First, update virtual albums to remove the photo
        val updatedAlbums = virtualAlbums.mapNotNull { album ->
            val updatedPhotoUris = album.photoUris.filterNot { it == uri }
            if (updatedPhotoUris.isEmpty()) {
                // If album becomes empty, return null to remove it
                null
            } else {
                album.copy(photoUris = updatedPhotoUris)
            }
        }

        // Update the albums list
        virtualAlbums.clear()
        virtualAlbums.addAll(updatedAlbums)

        // Then remove from mediaItems
        mediaItems.removeIf { it.baseUrl == uri }

        // Save both changes
        saveItems()
        saveVirtualAlbums()

        Log.d(TAG, """Removed photo with URI: $uri 
        • Previous count: $previousCount
        • New count: ${mediaItems.size}
        • Updated virtual albums: ${virtualAlbums.size}
        • Total photos in albums: ${virtualAlbums.sumOf { it.photoUris.size }}""".trimIndent())
    }

    private fun saveVirtualAlbums() {
        try {
            // Filter out albums with invalid URIs
            val validAlbums = virtualAlbums.map { album ->
                // Validate each URI in the album
                val validUris = album.photoUris.filter { uri ->
                    photoUriManager.hasValidPermission(Uri.parse(uri))
                }
                album.copy(photoUris = validUris)
            }.filter { it.photoUris.isNotEmpty() }

            // Create JSON array from valid albums
            val jsonArray = JSONArray()
            validAlbums.forEach { album ->
                jsonArray.put(JSONObject().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("photoUris", JSONArray(album.photoUris))
                    put("dateCreated", album.dateCreated)
                    put("isSelected", album.isSelected)
                })
            }

            preferences.edit()
                .putString(KEY_VIRTUAL_ALBUMS, jsonArray.toString())
                .apply()

            Log.d(TAG, """Saved virtual albums:
            • Total albums: ${validAlbums.size}
            • Selected albums: ${validAlbums.count { it.isSelected }}
            • Selection states: ${validAlbums.joinToString { "${it.id}: ${it.isSelected}" }}""".trimIndent())
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

                    val album = VirtualAlbum(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        photoUris = photoUris,
                        dateCreated = obj.getLong("dateCreated"),
                        isSelected = obj.optBoolean("isSelected", false)  // Default to false if not present
                    )
                    virtualAlbums.add(album)

                    //Log.d(TAG, """Loaded album:
                    //• Name: ${album.name}
                    //• Selection state: ${album.isSelected}
                    //• Photos: ${album.photoUris.size}""".trimIndent())
                }

                val selectedCount = virtualAlbums.count { it.isSelected }
                //Log.d(TAG, """Successfully loaded virtual albums:
                //• Total albums: ${virtualAlbums.size}
                //• Selected albums: $selectedCount
                //• Total photos: ${virtualAlbums.sumOf { it.photoUris.size }}""".trimIndent())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading virtual albums", e)
        }
    }

    fun addVirtualAlbum(album: VirtualAlbum) {
        virtualAlbums.add(album)
        saveVirtualAlbums()
        Log.d(TAG, "Added new virtual album: ${album.name} (selected: ${album.isSelected})")
    }

    fun cleanup() {
        // Cancel any ongoing coroutine operations
        repoScope.cancel()

        // Existing cleanup logic
        val previousCount = mediaItems.size
        mediaItems.clear()
        virtualAlbums.clear()
        preferences.edit()
            .remove(KEY_MEDIA_ITEMS)
            .remove(KEY_VIRTUAL_ALBUMS)
            .putBoolean(KEY_HAS_PHOTOS, false)
            .apply()
        Log.d(TAG, "Explicitly cleared all photos and virtual albums (previous count: $previousCount)")

        // Create a new scope for future operations
        repoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    }

    fun addPhotos(photos: List<MediaItem>, mode: PhotoAddMode = PhotoAddMode.MERGE) {
        Log.d(TAG, """Adding photos:
        • Mode: $mode
        • Current count: ${mediaItems.size}
        • New photos: ${photos.size}""".trimMargin())

        // Process photos based on their type
        val processedPhotos = photos.map { photo ->
            val uri = Uri.parse(photo.baseUrl)
            when {
                // For Google Photos URIs, we need to cache them
                photoUriManager.isGooglePhotosUri(uri) -> {
                    // Let the cached URI be resolved when needed
                    photo
                }
                else -> {
                    // Keep local URIs as-is, no caching needed
                    photo
                }
            }
        }

        when (mode) {
            PhotoAddMode.REPLACE -> {
                mediaItems.clear()
                mediaItems.addAll(processedPhotos)
                Log.d(TAG, "Replaced all photos with ${processedPhotos.size} new photos")
            }
            PhotoAddMode.APPEND, PhotoAddMode.MERGE -> {
                // For both APPEND and MERGE, ensure no duplicates
                val uniqueNewPhotos = processedPhotos.filterNot { newPhoto ->
                    mediaItems.any { existing ->
                        existing.baseUrl == newPhoto.baseUrl
                    }
                }
                mediaItems.addAll(uniqueNewPhotos)
                Log.d(TAG, """Added photos:
                • New unique photos: ${uniqueNewPhotos.size}
                • Duplicates filtered: ${processedPhotos.size - uniqueNewPhotos.size}
                • Total now: ${mediaItems.size}""".trimMargin())
            }
        }

        // Save changes if any photos were added
        saveItems()
        Log.d(TAG, "Final photo count: ${mediaItems.size}")
    }

    fun hasPhoto(uri: String): Boolean {
        return mediaItems.any { it.baseUrl == uri }
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

    fun getAllAlbums(): List<VirtualAlbum> {
        return virtualAlbums.toList()
    }

    fun syncVirtualAlbums(albums: List<VirtualAlbum>) {
        virtualAlbums.clear()
        virtualAlbums.addAll(albums)
        saveVirtualAlbums()
        // Force reload to ensure consistency
        loadVirtualAlbums()

        Log.d(TAG, """Synced albums to repository:
        • Total albums: ${virtualAlbums.size}
        • Selected albums: ${virtualAlbums.count { it.isSelected }}
        • Selection states: ${virtualAlbums.joinToString { "${it.id}: ${it.isSelected}" }}""".trimIndent())
    }
}