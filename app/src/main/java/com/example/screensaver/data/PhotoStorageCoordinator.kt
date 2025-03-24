package com.example.screensaver.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.screensaver.PhotoRepository
import com.example.screensaver.models.MediaItem
import com.example.screensaver.photos.PhotoUriManager
import com.example.screensaver.photos.VirtualAlbum
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoStorageCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoUriManager: PhotoUriManager,
    private val preferences: AppPreferences,
    private val coroutineScope: CoroutineScope,
    private val photoCache: PhotoCache,
    private val secureStorage: SecureStorage,
    private val appDataManager: AppDataManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _photos = MutableStateFlow<List<MediaItem>>(emptyList())
    val photos: StateFlow<List<MediaItem>> = _photos.asStateFlow()

    private val _virtualAlbums = MutableStateFlow<List<VirtualAlbum>>(emptyList())
    val virtualAlbums: StateFlow<List<VirtualAlbum>> = _virtualAlbums.asStateFlow()

    private val photoLoadStates = ConcurrentHashMap<String, MediaItem.LoadState>()

    private val KEY_VIRTUAL_ALBUMS = "virtual_albums"

    private val _loadingState = MutableStateFlow(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    init {
        scope.launch {
            synchronizeAllSources()
        }
    }

    suspend fun validateAllPhotos() {
        try {
            val currentPhotos = getAllPhotos()
            Log.d(TAG, "Validating ${currentPhotos.size} stored photos")

            // First try to take permissions for all URIs
            currentPhotos.forEach { photo ->
                try {
                    val uri = Uri.parse(photo.baseUrl)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to take permission for: ${photo.baseUrl}", e)
                }
            }

            // Then validate URIs
            val validPhotoUris = photoUriManager.validateUris(currentPhotos.map { it.baseUrl })

            // If we have invalid URIs, try one more time with fresh permissions
            if (validPhotoUris.size < currentPhotos.size) {
                Log.d(TAG, "Found ${currentPhotos.size - validPhotoUris.size} invalid URIs, retrying...")

                // Try taking permissions again for invalid URIs
                currentPhotos
                    .filter { !validPhotoUris.contains(it.baseUrl) }
                    .forEach { photo ->
                        try {
                            val uri = Uri.parse(photo.baseUrl)
                            if (uri.scheme == "content") {
                                photoUriManager.takePersistablePermission(uri)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to take permission on retry for: ${photo.baseUrl}", e)
                        }
                    }

                // Validate again
                val retryValidUris = photoUriManager.validateUris(currentPhotos.map { it.baseUrl })

                if (retryValidUris.size > validPhotoUris.size) {
                    Log.d(TAG, "Recovered ${retryValidUris.size - validPhotoUris.size} URIs on retry")
                }

                // Update storage with valid photos
                val validPhotos = currentPhotos.filter { photo ->
                    retryValidUris.contains(photo.baseUrl)
                }

                clearPhotos()
                if (validPhotos.isNotEmpty()) {
                    addPhotos(validPhotos)
                }

                Log.d(TAG, "Updated storage with ${validPhotos.size} valid photos")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating photos", e)
            throw e
        }
    }

    fun addVirtualAlbums(albums: List<VirtualAlbum>) {
        try {
            _virtualAlbums.value = albums
            saveVirtualAlbums()
            Log.d(TAG, "Added ${albums.size} virtual albums to coordinator")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding virtual albums", e)
            throw e
        }
    }

    private fun saveVirtualAlbums() {
        try {
            val jsonArray = JSONArray().apply {
                _virtualAlbums.value.forEach { album ->
                    put(JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("photoUris", JSONArray(album.photoUris))
                        put("dateCreated", album.dateCreated)
                        put("isSelected", album.isSelected)
                    })
                }
            }

            // Use preferences to save
            preferences.saveVirtualAlbums(_virtualAlbums.value)

            Log.d(TAG, """Saved virtual albums:
            • Total albums: ${_virtualAlbums.value.size}
            • Selected albums: ${_virtualAlbums.value.count { it.isSelected }}
            • Selection states: ${_virtualAlbums.value.joinToString { "${it.id}: ${it.isSelected}" }}""".trimIndent())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving virtual albums", e)
        }
    }

    private fun loadVirtualAlbums() {
        try {
            val json = preferences.getString(KEY_VIRTUAL_ALBUMS, "[]")
            if (json.isNotEmpty()) {
                val jsonArray = JSONArray(json)
                val albums = mutableListOf<VirtualAlbum>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val urisArray = obj.getJSONArray("photoUris")
                    val photoUris = mutableListOf<String>()
                    for (j in 0 until urisArray.length()) {
                        photoUris.add(urisArray.getString(j))
                    }

                    albums.add(VirtualAlbum(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        photoUris = photoUris,
                        dateCreated = obj.getLong("dateCreated"),
                        isSelected = obj.optBoolean("isSelected", false)
                    ))
                }

                _virtualAlbums.value = albums
                Log.d(TAG, """Loaded virtual albums:
            • Total albums: ${albums.size}
            • Selected albums: ${albums.count { it.isSelected }}
            • Total photos: ${albums.sumOf { it.photoUris.size }}""".trimIndent())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading virtual albums", e)
        }
    }

    suspend fun clearPhotos() {
        withContext(Dispatchers.IO) {
            try {
                _loadingState.value = LoadingState.LOADING
                _photos.value = emptyList()
                photoLoadStates.clear()
                _loadingState.value = LoadingState.SUCCESS
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing photos", e)
                _loadingState.value = LoadingState.ERROR
            }
        }
    }

    // Update init block to load virtual albums
    init {
        scope.launch {
            loadVirtualAlbums()
            synchronizeAllSources()
        }
    }

    private suspend fun synchronizeAllSources() {
        val currentTime = "${java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}"
        val currentUser = "cosminibudrica" // You can get this from your auth system

        try {
            _loadingState.value = LoadingState.LOADING

            // Get all recently accessed URIs first
            val recentUris = preferences.getRecentlyAccessedUris()
            Log.d(TAG, "Processing ${recentUris.size} recent URIs at $currentTime")

            // Take permissions for recent URIs
            recentUris.forEach { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking permission during sync at $currentTime: $uriString", e)
                }
            }

            // 1. Get URIs from PhotoUriManager's recently accessed URIs
            val managedUris = photoUriManager.validateUris(recentUris.toList())
            Log.d(TAG, "Found ${managedUris.size} valid managed URIs at $currentTime")

            // 2. Get picked photos from preferences
            val pickedUris = preferences.getPickedUris()
            Log.d(TAG, "Found ${pickedUris.size} picked URIs at $currentTime")

            // Take permissions for picked URIs
            pickedUris.forEach { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking permission for picked URI at $currentTime: $uriString", e)
                }
            }

            // 3. Load virtual albums
            val virtualAlbumPhotos = _virtualAlbums.value.flatMap { album -> album.photoUris }
            Log.d(TAG, "Found ${virtualAlbumPhotos.size} virtual album photos at $currentTime")

            // Take permissions for virtual album photos
            virtualAlbumPhotos.forEach { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking permission for virtual album photo at $currentTime: $uriString", e)
                }
            }

            // 4. Get PhotoCache state
            val lastCachedPhoto = context.getSharedPreferences("photo_cache_prefs", Context.MODE_PRIVATE)
                .getString("last_known_photo", null)
            Log.d(TAG, "Found cached photo: ${lastCachedPhoto != null} at $currentTime")

            if (lastCachedPhoto != null) {
                try {
                    val uri = Uri.parse(lastCachedPhoto)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking permission for cached photo at $currentTime: $lastCachedPhoto", e)
                }
            }

            // 5. Get AppDataManager state
            val appState = appDataManager.getCurrentState()
            val appStatePhotos = appState.photoSources?.toList() ?: emptyList()
            Log.d(TAG, "Found ${appStatePhotos.size} app state photos at $currentTime")

            // Take permissions for app state photos
            appStatePhotos.forEach { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking permission for app state photo at $currentTime: $uriString", e)
                }
            }

            // 6. Get SecureStorage photos
            val securePhotos = try {
                secureStorage.getSecurely("photo_uris")?.split(",") ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading secure storage photos at $currentTime", e)
                emptyList()
            }
            Log.d(TAG, "Found ${securePhotos.size} secure storage photos at $currentTime")

            // Take permissions for secure storage photos
            securePhotos.forEach { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") {
                        photoUriManager.takePersistablePermission(uri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error taking permission for secure storage URI at $currentTime: $uriString", e)
                }
            }

            // Merge all sources and remove duplicates
            val allPhotos = mutableSetOf<MediaItem>()

            // Add managed URIs
            managedUris.forEach { uriString ->
                if (!allPhotos.any { it.baseUrl == uriString }) {
                    allPhotos.add(createMediaItem(uriString, "managed_photos"))
                }
            }

            // Add picked URIs
            pickedUris.forEach { uriString ->
                if (!allPhotos.any { it.baseUrl == uriString.toString() }) {
                    allPhotos.add(createMediaItem(uriString.toString(), "picked_photos"))
                }
            }

            // Add virtual album photos
            virtualAlbumPhotos.forEach { uriString ->
                if (!allPhotos.any { it.baseUrl == uriString }) {
                    allPhotos.add(createMediaItem(uriString, "virtual_album_photos"))
                }
            }

            // Add cached photo if valid
            if (lastCachedPhoto != null && !allPhotos.any { it.baseUrl == lastCachedPhoto } &&
                photoUriManager.validateUri(Uri.parse(lastCachedPhoto))) {
                allPhotos.add(createMediaItem(lastCachedPhoto, "cached_photos"))
            }

            // Add app state photos
            appStatePhotos.forEach { uriString ->
                if (!allPhotos.any { it.baseUrl == uriString }) {
                    allPhotos.add(createMediaItem(uriString, "app_state_photos"))
                }
            }

            // Add secure storage photos
            securePhotos.forEach { uriString ->
                if (!allPhotos.any { it.baseUrl == uriString }) {
                    allPhotos.add(createMediaItem(uriString, "secure_storage_photos"))
                }
            }

            Log.d(TAG, """Synchronized sources at $currentTime:
            • Total photos: ${allPhotos.size}
            • Managed URIs: ${managedUris.size}
            • Picked URIs: ${pickedUris.size}
            • Virtual album photos: ${virtualAlbumPhotos.size}
            • Cached photos: ${if (lastCachedPhoto != null) 1 else 0}
            • App state photos: ${appStatePhotos.size}
            • Secure storage photos: ${securePhotos.size}
            • Duplicates removed: ${(
                    managedUris.size +
                            pickedUris.size +
                            virtualAlbumPhotos.size +
                            (if (lastCachedPhoto != null) 1 else 0) +
                            appStatePhotos.size +
                            securePhotos.size
                    ) - allPhotos.size}
            • Current user: $currentUser
        """.trimIndent())

            // Update the shared state
            _photos.value = allPhotos.toList()

            // Persist URIs to preferences and ensure permissions
            updateUriPermissions(allPhotos.toList())

            _loadingState.value = LoadingState.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "Error synchronizing sources at $currentTime", e)
            _loadingState.value = LoadingState.ERROR
        }
    }

    private fun createMediaItem(uriString: String, albumId: String): MediaItem {
        return MediaItem(
            id = uriString,
            albumId = albumId,
            baseUrl = uriString,
            mimeType = "image/*",
            width = 0,
            height = 0,
            description = null,
            createdAt = System.currentTimeMillis(),
            loadState = photoLoadStates[uriString] ?: MediaItem.LoadState.IDLE
        )
    }

    private suspend fun updateUriPermissions(photos: List<MediaItem>) {
        withContext(Dispatchers.IO) {
            try {
                photos.forEach { photo ->
                    try {
                        val uri = Uri.parse(photo.baseUrl)
                        if (uri.scheme == "content") {
                            // Take permission before adding to recent
                            photoUriManager.takePersistablePermission(uri)
                            preferences.addRecentlyAccessedUri(photo.baseUrl)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating permission for: ${photo.baseUrl}", e)
                    }
                }

                // Update picked URIs in preferences
                val pickedPhotos = photos
                    .filter { it.albumId == "picked_photos" }
                    .mapNotNull {
                        try {
                            Uri.parse(it.baseUrl)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing URI: ${it.baseUrl}", e)
                            null
                        }
                    }
                    .toSet()

                preferences.savePickedUris(pickedPhotos)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating URI permissions", e)
            }
        }
    }

    suspend fun addPhotos(newPhotos: List<MediaItem>) {
        withContext(Dispatchers.IO) {
            try {
                _loadingState.value = LoadingState.LOADING

                // CHANGE HERE: Process URIs first instead of just taking permissions
                val processedPhotos = newPhotos.mapNotNull { photo ->
                    try {
                        val uri = Uri.parse(photo.baseUrl)
                        // Use processUri which has better handling especially for Android 11
                        val uriData = photoUriManager.processUri(uri)
                        if (uriData != null) {
                            // Only add photos that were successfully processed
                            photo
                        } else {
                            Log.e(TAG, "Failed to process URI: ${photo.baseUrl}")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing URI: ${photo.baseUrl}", e)
                        null
                    }
                }

                val current = _photos.value.toMutableList()
                current.addAll(processedPhotos)
                _photos.value = current

                // IMPORTANT: Force a small delay to ensure Android processes the permissions
                kotlinx.coroutines.delay(500)

                _loadingState.value = LoadingState.SUCCESS
            } catch (e: Exception) {
                Log.e(TAG, "Error adding photos", e)
                _loadingState.value = LoadingState.ERROR
            }
        }
    }

    suspend fun removePhotos(photoIds: List<String>) {
        withContext(Dispatchers.IO) {
            try {
                _loadingState.value = LoadingState.LOADING

                val current = _photos.value.toMutableList()
                current.removeAll { it.id in photoIds }
                _photos.value = current

                // Clear load states for removed photos
                photoIds.forEach { photoLoadStates.remove(it) }

                _loadingState.value = LoadingState.SUCCESS
            } catch (e: Exception) {
                Log.e(TAG, "Error removing photos", e)
                _loadingState.value = LoadingState.ERROR
            }
        }
    }

    suspend fun updatePhotoLoadState(photoId: String, state: MediaItem.LoadState) {
        withContext(Dispatchers.IO) {
            photoLoadStates[photoId] = state

            // Update the photo in the current list
            val current = _photos.value.toMutableList()
            val index = current.indexOfFirst { it.id == photoId }
            if (index != -1) {
                current[index] = current[index].copy(loadState = state)
                _photos.value = current
            }
        }
    }

    fun updateVirtualAlbumSelection(albumId: String, isSelected: Boolean) {
        coroutineScope.launch {
            try {
                val currentAlbums = _virtualAlbums.value.toMutableList()
                val index = currentAlbums.indexOfFirst { it.id == albumId }
                if (index != -1) {
                    currentAlbums[index] = currentAlbums[index].copy(isSelected = isSelected)
                    _virtualAlbums.value = currentAlbums
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating album selection", e)
            }
        }
    }

    fun getAllPhotos(): List<MediaItem> = _photos.value

    suspend fun getPhotosByAlbum(albumId: String): List<MediaItem> {
        return _photos.value.filter { it.albumId == albumId }
    }

    suspend fun validatePhoto(photoId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val photo = _photos.value.find { it.id == photoId } ?: return@withContext false
                val uri = Uri.parse(photo.baseUrl)
                photoUriManager.hasValidPermission(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Error validating photo: $photoId", e)
                false
            }
        }
    }

    suspend fun refresh() {
        synchronizeAllSources()
    }

    suspend fun cleanup() {
        withContext(Dispatchers.IO) {
            photoLoadStates.clear()
            _photos.value = emptyList()
            _loadingState.value = LoadingState.IDLE
        }
    }

    enum class LoadingState {
        IDLE,
        LOADING,
        SUCCESS,
        ERROR
    }

    companion object {
        private const val TAG = "PhotoStorageCoordinator"
    }
}