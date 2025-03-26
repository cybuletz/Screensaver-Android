package com.photostreamr.photos

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.PhotoRepository
import com.example.screensaver.models.MediaItem
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
class PhotoManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val secureStorage: SecureStorage,
    private val photoRepository: PhotoRepository
) : ViewModel() {

    private val persistentPhotoCache: PersistentPhotoCache
        get() = photoRepository.persistentPhotoCache


    private val _state = MutableStateFlow<PhotoManagerState>(PhotoManagerState.Idle)
    private val _photos = MutableStateFlow<List<ManagedPhoto>>(emptyList())
    private val _virtualAlbums = MutableStateFlow<List<VirtualAlbum>>(emptyList())
    private val _selectedCount = MutableStateFlow(0)

    val state: StateFlow<PhotoManagerState> = _state.asStateFlow()
    val photos: StateFlow<List<ManagedPhoto>> = _photos.asStateFlow()
    val virtualAlbums: StateFlow<List<VirtualAlbum>> = _virtualAlbums.asStateFlow()
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    data class ManagedPhoto(
        val id: String,
        val uri: String,
        val sourceType: PhotoSourceType,
        val albumId: String,
        val dateAdded: Long,
        val isSelected: Boolean = false,
        val hasLoadError: Boolean = false
    )

    enum class PhotoSourceType {
        LOCAL_PICKED,
        LOCAL_ALBUM,
        GOOGLE_PHOTOS,
        VIRTUAL_ALBUM
    }

    sealed class PhotoManagerState {
        object Idle : PhotoManagerState()
        object Loading : PhotoManagerState()
        object Empty : PhotoManagerState()
        data class Success(val message: String) : PhotoManagerState()
        data class Error(val message: String) : PhotoManagerState()
        data class Processing(val message: String) : PhotoManagerState()
    }

    enum class SortOption {
        DATE_DESC,
        DATE_ASC,
        SOURCE
    }

    companion object {
        private const val TAG = "PhotoManagerViewModel"
    }

    init {
        viewModelScope.launch {
            try {
                // First get all albums from repository
                val repoAlbums = photoRepository.getAllAlbums().map { repoAlbum ->
                    VirtualAlbum(
                        id = repoAlbum.id,
                        name = repoAlbum.name,
                        photoUris = repoAlbum.photoUris,
                        dateCreated = repoAlbum.dateCreated,
                        isSelected = repoAlbum.isSelected
                    )
                }

                // Then load from preferences
                val savedAlbums = preferences.getString("virtual_albums", "[]")
                val jsonArray = JSONArray(savedAlbums)

                // Use a map to ensure uniqueness by ID
                val uniqueAlbums = mutableMapOf<String, VirtualAlbum>()

                // Add repo albums first
                repoAlbums.forEach { album ->
                    uniqueAlbums[album.id] = album
                }

                // Add preference albums, potentially overwriting repo versions
                for (i in 0 until jsonArray.length()) {
                    val albumJson = jsonArray.getJSONObject(i)
                    val albumId = albumJson.getString("id")
                    val photoUrisArray = albumJson.getJSONArray("photoUris")
                    val photoUris = mutableListOf<String>()
                    for (j in 0 until photoUrisArray.length()) {
                        photoUris.add(photoUrisArray.getString(j))
                    }

                    uniqueAlbums[albumId] = VirtualAlbum(
                        id = albumId,
                        name = albumJson.getString("name"),
                        photoUris = photoUris,
                        dateCreated = albumJson.getLong("dateCreated"),
                        isSelected = albumJson.optBoolean("isSelected", false)
                    )
                }

                // Update the state with unique albums
                _virtualAlbums.value = uniqueAlbums.values.toList()

                // Sync back to repository
                photoRepository.syncVirtualAlbums(_virtualAlbums.value)

                // Now load initial state
                loadInitialState()

            } catch (e: Exception) {
                Log.e(TAG, "Error loading virtual albums", e)
            }
        }
    }

    fun markPhotoLoadError(photoId: String, error: Exception?) {
        viewModelScope.launch {
            try {
                // Update the photos list with the error state
                val currentPhotos = _photos.value
                val updatedPhotos = currentPhotos.map { photo ->
                    if (photo.id == photoId) {
                        photo.copy(hasLoadError = true)
                    } else {
                        photo
                    }
                }

                // Only update if needed
                if (updatedPhotos != currentPhotos) {
                    _photos.value = updatedPhotos
                }

                // Log the error
                error?.let {
                    Log.e(TAG, "Photo load error for $photoId: ${it.message}", it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in markPhotoLoadError", e)
            }
        }
    }

    fun togglePhotoSelection(photoId: String) {
        viewModelScope.launch {
            val currentPhotos = _photos.value.toMutableList()
            val photoIndex = currentPhotos.indexOfFirst { it.id == photoId }

            if (photoIndex != -1) {
                currentPhotos[photoIndex] = currentPhotos[photoIndex].copy(
                    isSelected = !currentPhotos[photoIndex].isSelected
                )
                // Update selected count first
                _selectedCount.value = currentPhotos.count { it.isSelected }

                // Then update photos without triggering a full refresh
                withContext(Dispatchers.Main) {
                    _photos.value = currentPhotos.toList()
                }
            }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading
                Log.d(TAG, "Starting to load initial state")
                Log.d(TAG, "Checking preferences for picked URIs...")
                val pickedUrisDebug = preferences.getPickedUris()
                Log.d(TAG, "Found ${pickedUrisDebug.size} picked URIs in preferences")
                pickedUrisDebug.take(5).forEach { uri ->
                    Log.d(TAG, "Sample picked URI: $uri")
                }

                // First get all albums from repository and preferences
                val repoAlbums = photoRepository.getAllAlbums()
                Log.d(TAG, "Loaded ${repoAlbums.size} albums from repository")

                val virtualAlbums = repoAlbums.map { repoAlbum ->
                    VirtualAlbum(
                        id = repoAlbum.id,
                        name = repoAlbum.name,
                        photoUris = repoAlbum.photoUris,
                        dateCreated = repoAlbum.dateCreated,
                        isSelected = repoAlbum.isSelected
                    )
                }

                // Update virtual albums state with repository data
                _virtualAlbums.value = virtualAlbums

                // Create a set of existing photo URIs to prevent duplicates
                val existingPhotoUris = mutableSetOf<String>()
                val photos = mutableListOf<ManagedPhoto>()

                // Get all available photos for management
                val availablePhotos = photoRepository.getAllPhotos()
                Log.d(TAG, "PhotoRepository has ${availablePhotos.size} photos")

                // Debug: Log the first few photo URLs to see what we're dealing with
                availablePhotos.take(5).forEach {
                    Log.d(TAG, "PhotoRepository photo: ${it.baseUrl}")
                }

                // Convert existing photos from PhotoRepository, avoiding duplicates
                availablePhotos.forEach { item ->
                    if (!existingPhotoUris.contains(item.baseUrl)) {
                        existingPhotoUris.add(item.baseUrl)
                        val sourceType = when {
                            item.baseUrl.contains("com.google.android.apps.photos.cloudpicker") -> PhotoSourceType.GOOGLE_PHOTOS
                            item.baseUrl.contains("content://media/picker") -> PhotoSourceType.LOCAL_PICKED
                            item.baseUrl.contains("content://media/external") -> PhotoSourceType.LOCAL_ALBUM
                            else -> PhotoSourceType.VIRTUAL_ALBUM
                        }

                        photos.add(ManagedPhoto(
                            id = item.baseUrl,
                            uri = item.baseUrl,
                            sourceType = sourceType,
                            albumId = when(sourceType) {
                                PhotoSourceType.GOOGLE_PHOTOS -> "google_photos"
                                PhotoSourceType.LOCAL_PICKED -> "picked_photos"
                                PhotoSourceType.LOCAL_ALBUM -> "local_albums"
                                PhotoSourceType.VIRTUAL_ALBUM -> "virtual_albums"
                            },
                            dateAdded = item.createdAt
                        ))
                        Log.d(TAG, "Added photo from PhotoRepository: ${item.baseUrl} (type: $sourceType)")
                    }
                }

                // Add photos from picked URIs if they're not already included
                val pickedUris = preferences.getPickedUris()
                Log.d(TAG, "Found ${pickedUris.size} picked URIs in preferences")

                // Debug: Log the first few picked URIs to see what we're dealing with
                pickedUris.take(5).forEach {
                    Log.d(TAG, "Picked URI from prefs: $it")
                }

                pickedUris.forEach { uriString ->
                    if (!existingPhotoUris.contains(uriString)) {
                        existingPhotoUris.add(uriString)
                        val sourceType = when {
                            uriString.contains("com.google.android.apps.photos.cloudpicker") -> PhotoSourceType.GOOGLE_PHOTOS
                            else -> PhotoSourceType.LOCAL_PICKED
                        }
                        photos.add(ManagedPhoto(
                            id = uriString,
                            uri = uriString,
                            sourceType = sourceType,
                            albumId = if (sourceType == PhotoSourceType.GOOGLE_PHOTOS) "google_picked" else "local_picked",
                            dateAdded = System.currentTimeMillis()
                        ))
                        Log.d(TAG, "Added picked photo: $uriString with source type: $sourceType")

                        // Make sure it's in the PhotoRepository if it's not already there
                        if (!photoRepository.hasPhoto(uriString)) {
                            val mediaItem = MediaItem(
                                id = uriString,
                                albumId = "local_picked",
                                baseUrl = uriString,
                                mimeType = "image/*",
                                width = 0,
                                height = 0,
                                description = null,
                                createdAt = System.currentTimeMillis(),
                                loadState = MediaItem.LoadState.IDLE
                            )
                            photoRepository.addPhotos(listOf(mediaItem), PhotoRepository.PhotoAddMode.MERGE)
                            Log.d(TAG, "Added missing photo to PhotoRepository: $uriString")
                        }
                    }
                }

                // Load local photos from selected albums if any
                val selectedAlbumIds = preferences.getSelectedAlbumIds()
                if (selectedAlbumIds.isNotEmpty()) {
                    Log.d(TAG, "Loading photos from ${selectedAlbumIds.size} local albums")
                    loadLocalPhotos().forEach { photo ->
                        if (!existingPhotoUris.contains(photo.uri)) {
                            existingPhotoUris.add(photo.uri)
                            photos.add(photo)
                            Log.d(TAG, "Added local album photo: ${photo.uri}")
                        }
                    }
                }

                // Log detailed photo counts by source
                Log.d(TAG, """Photos loaded by source:
            • Total: ${photos.size}
            • Google Photos: ${photos.count { it.sourceType == PhotoSourceType.GOOGLE_PHOTOS }}
            • Local Picked: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_PICKED }}
            • Local Album: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_ALBUM }}
            • Virtual Album: ${photos.count { it.sourceType == PhotoSourceType.VIRTUAL_ALBUM }}
        """.trimIndent())

                if (photos.isNotEmpty()) {
                    _photos.value = photos.sortedByDescending { it.dateAdded }
                    _state.value = PhotoManagerState.Idle
                    Log.d(TAG, "PhotoManagerViewModel state set to Idle with ${photos.size} photos")
                } else {
                    _photos.value = emptyList()
                    _state.value = PhotoManagerState.Empty
                    Log.d(TAG, "No photos found - PhotoManagerViewModel state set to Empty")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading initial state", e)
                _state.value = PhotoManagerState.Error("Failed to load photos: ${e.message}")
                _photos.value = emptyList()
            }
        }
    }

    private suspend fun loadLocalPhotos(): List<ManagedPhoto> = withContext(Dispatchers.IO) {
        val photos = mutableListOf<ManagedPhoto>()
        val selectedAlbumIds = preferences.getSelectedAlbumIds()

        try {
            // First load picked photos
            preferences.getPickedUris().forEach { uriString ->
                try {
                    Uri.parse(uriString)?.let { uri ->
                        context.contentResolver.query(
                            uri,
                            arrayOf(
                                MediaStore.Images.Media._ID,
                                MediaStore.Images.Media.DATE_ADDED,
                                MediaStore.Images.Media.DISPLAY_NAME
                            ),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                                val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert to milliseconds

                                photos.add(
                                    ManagedPhoto(
                                        id = uri.toString(),
                                        uri = uri.toString(),
                                        sourceType = PhotoSourceType.LOCAL_PICKED,
                                        albumId = "picked_photos",
                                        dateAdded = dateAdded
                                    )
                                )
                            } else {
                                // If cursor is empty but URI exists, add with current timestamp
                                photos.add(
                                    ManagedPhoto(
                                        id = uri.toString(),
                                        uri = uri.toString(),
                                        sourceType = PhotoSourceType.LOCAL_PICKED,
                                        albumId = "picked_photos",
                                        dateAdded = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading picked photo: $uriString", e)
                }
            }

            // Then load photos from selected albums
            selectedAlbumIds.forEach { albumId ->
                try {
                    val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
                    val selectionArgs = arrayOf(albumId)
                    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                    context.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.Images.Media._ID,
                            MediaStore.Images.Media.DATE_ADDED
                        ),
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                            val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            )

                            photos.add(
                                ManagedPhoto(
                                    id = id.toString(),
                                    uri = contentUri.toString(),
                                    sourceType = PhotoSourceType.LOCAL_ALBUM,
                                    albumId = albumId,
                                    dateAdded = dateAdded * 1000 // Convert to milliseconds
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photos from album $albumId", e)
                }
            }

            Log.d(TAG, """Local photos loaded:
            • Total: ${photos.size}
            • Picked: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_PICKED }}
            • Album: ${photos.count { it.sourceType == PhotoSourceType.LOCAL_ALBUM }}
        """.trimIndent())

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadLocalPhotos", e)
        }

        photos
    }

    fun selectAllPhotos() {
        val updatedPhotos = _photos.value.map { it.copy(isSelected = true) }
        _photos.value = updatedPhotos
        _selectedCount.value = updatedPhotos.size
    }

    fun deselectAllPhotos() {
        val updatedPhotos = _photos.value.map { it.copy(isSelected = false) }
        _photos.value = updatedPhotos
        _selectedCount.value = 0
    }

    fun createVirtualAlbum(name: String, isSelected: Boolean = false) {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading

                val selectedPhotos = _photos.value.filter { it.isSelected }
                if (selectedPhotos.isEmpty()) {
                    _state.value = PhotoManagerState.Error("No photos selected")
                    return@launch
                }

                val newAlbum = VirtualAlbum(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    photoUris = selectedPhotos.map { it.uri },
                    dateCreated = System.currentTimeMillis(),
                    isSelected = isSelected  // Use the passed parameter
                )

                // Add to PhotoRepository
                photoRepository.addVirtualAlbum(newAlbum)

                // Update local state
                val currentAlbums = _virtualAlbums.value.toMutableList()
                currentAlbums.add(newAlbum)
                _virtualAlbums.value = currentAlbums

                // Save immediately after creation
                saveVirtualAlbums(currentAlbums)

                clearSelection()
                _state.value = PhotoManagerState.Success("Album created successfully")

                Log.d(TAG, """Created and saved album: 
                • Name: ${newAlbum.name}
                • Photos: ${newAlbum.photoUris.size}
                • Selected: $isSelected""".trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "Error creating virtual album", e)
                _state.value = PhotoManagerState.Error("Failed to create album")
            }
        }
    }

    fun reloadVirtualAlbums() {
        viewModelScope.launch {
            try {
                val savedAlbums = preferences.getString("virtual_albums", "[]")
                val jsonArray = JSONArray(savedAlbums)
                val albums = mutableListOf<VirtualAlbum>()

                for (i in 0 until jsonArray.length()) {
                    val albumJson = jsonArray.getJSONObject(i)
                    val photoUrisArray = albumJson.getJSONArray("photoUris")
                    val photoUris = mutableListOf<String>()
                    for (j in 0 until photoUrisArray.length()) {
                        photoUris.add(photoUrisArray.getString(j))
                    }

                    albums.add(VirtualAlbum(
                        id = albumJson.getString("id"),
                        name = albumJson.getString("name"),
                        photoUris = photoUris,
                        dateCreated = albumJson.getLong("dateCreated"),
                        isSelected = albumJson.optBoolean("isSelected", false)
                    ))
                }

                Log.d(TAG, "Reloaded ${albums.size} albums from preferences")
                _virtualAlbums.value = albums
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading virtual albums", e)
            }
        }
    }

    fun deleteVirtualAlbum(albumId: String) {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading

                val currentAlbums = _virtualAlbums.value.toMutableList()
                val albumIndex = currentAlbums.indexOfFirst { it.id == albumId }

                if (albumIndex != -1) {
                    currentAlbums.removeAt(albumIndex)
                    _virtualAlbums.value = currentAlbums
                    saveVirtualAlbums(currentAlbums)
                    _state.value = PhotoManagerState.Success("Album deleted")
                } else {
                    _state.value = PhotoManagerState.Error("Album not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting virtual album", e)
                _state.value = PhotoManagerState.Error("Failed to delete album")
            }
        }
    }

    fun appendToLatestDefaultAlbum(newPhotos: List<String>) {
        viewModelScope.launch {
            try {
                val existingAlbums = _virtualAlbums.value
                val latestDefaultAlbum = existingAlbums
                    .filter { it.name.startsWith("Default Album") }
                    .maxByOrNull { it.dateCreated }

                if (latestDefaultAlbum != null) {
                    // Create updated album with new photos
                    val updatedAlbum = latestDefaultAlbum.copy(
                        photoUris = (latestDefaultAlbum.photoUris + newPhotos).distinct()
                    )

                    // Update albums list
                    val updatedAlbums = existingAlbums.map {
                        if (it.id == latestDefaultAlbum.id) updatedAlbum else it
                    }
                    _virtualAlbums.value = updatedAlbums

                    // Update repository
                    photoRepository.syncVirtualAlbums(updatedAlbums)
                    saveVirtualAlbums(updatedAlbums)

                    Log.d(TAG, """Updated album ${latestDefaultAlbum.name}:
                    • Previous photos: ${latestDefaultAlbum.photoUris.size}
                    • Added photos: ${newPhotos.size}
                    • New total: ${updatedAlbum.photoUris.size}
                """.trimIndent())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error appending to default album", e)
            }
        }
    }

    fun getPhotosNotInAlbums(): List<String> {
        val allAlbumPhotos = _virtualAlbums.value
            .flatMap { it.photoUris }
            .toSet()

        return _photos.value
            .map { it.uri }
            .filterNot { allAlbumPhotos.contains(it) }
    }

    fun cacheGooglePhotos(photos: List<ManagedPhoto>) {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading

                // Filter for Google Photos URIs
                val googlePhotos = photos.filter {
                    it.uri.contains("photos.google.com") ||
                            it.uri.contains("googleusercontent.com")
                }

                if (googlePhotos.isEmpty()) {
                    _state.value = PhotoManagerState.Idle
                    return@launch
                }

                // Show caching notification
                _state.value = PhotoManagerState.Processing("Caching Google Photos...")

                // Start caching
                persistentPhotoCache.cachePhotos(googlePhotos.map { it.uri })
                    .collect { progress ->
                        when (progress) {
                            is PersistentPhotoCache.CachingProgress.InProgress -> {
                                val percentComplete = (progress.progress * 100).toInt()
                                _state.value = PhotoManagerState.Processing(
                                    "Caching photos: $percentComplete% (${progress.completed}/${progress.total})"
                                )
                            }
                            is PersistentPhotoCache.CachingProgress.Complete -> {
                                Log.d(TAG, "Caching complete: ${progress.succeeded} succeeded, " +
                                        "${progress.failed} failed, ${progress.alreadyCached} already cached")
                                _state.value = PhotoManagerState.Idle
                            }
                            is PersistentPhotoCache.CachingProgress.Failed -> {
                                Log.e(TAG, "Caching failed: ${progress.reason}")
                                _state.value = PhotoManagerState.Error("Failed to cache photos: ${progress.reason}")
                            }
                            else -> {
                                // Handle other states
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error caching Google Photos", e)
                _state.value = PhotoManagerState.Error("Error caching photos: ${e.message}")
            }
        }
    }

    fun sortPhotos(option: SortOption) {
        val sorted = when (option) {
            SortOption.DATE_DESC -> _photos.value.sortedByDescending { it.dateAdded }
            SortOption.DATE_ASC -> _photos.value.sortedBy { it.dateAdded }
            SortOption.SOURCE -> _photos.value.sortedBy { it.sourceType.name }
        }
        _photos.value = sorted
    }

    fun groupPhotosBySource() {
        val grouped = _photos.value.sortedBy {
            when (it.sourceType) {
                PhotoSourceType.GOOGLE_PHOTOS -> 0
                PhotoSourceType.LOCAL_PICKED -> 1
                PhotoSourceType.LOCAL_ALBUM -> 2
                PhotoSourceType.VIRTUAL_ALBUM -> 3
            }
        }
        _photos.value = grouped
    }

    fun removeSelectedPhotos() {
        viewModelScope.launch {
            try {
                _state.value = PhotoManagerState.Loading
                val selectedPhotos = _photos.value.filter { it.isSelected }

                // Remove from PhotoRepository
                selectedPhotos.forEach { photo ->
                    photoRepository.removePhoto(photo.uri)

                    // Remove from picked URIs if it was individually picked
                    if (photo.sourceType == PhotoSourceType.LOCAL_PICKED) {
                        preferences.removePickedUri(photo.uri)
                    }
                }

                // Remove from virtual albums
                val currentAlbums = _virtualAlbums.value.toMutableList()
                val updatedAlbums = currentAlbums.map { album ->
                    album.copy(
                        photoUris = album.photoUris.filterNot { uri ->
                            selectedPhotos.any { photo -> photo.uri == uri }
                        }
                    )
                }.filter { it.photoUris.isNotEmpty() } // Remove empty albums

                _virtualAlbums.value = updatedAlbums
                saveVirtualAlbums() // Save the updated albums

                // Update local photo selections
                val currentLocalPhotos = preferences.getLocalSelectedPhotos()
                val updatedLocalPhotos = currentLocalPhotos.filterNot { uri ->
                    selectedPhotos.any { photo -> photo.uri == uri }
                }.toSet()
                preferences.updateLocalSelectedPhotos(updatedLocalPhotos)

                // Update album selections if needed
                val currentAlbumIds = preferences.getSelectedAlbumIds()
                val remainingPhotos = _photos.value.filterNot { it.isSelected }
                val albumsToKeep = remainingPhotos
                    .filter { it.sourceType == PhotoSourceType.LOCAL_ALBUM }
                    .mapNotNull { it.albumId }
                    .toSet()

                preferences.setSelectedAlbumIds(currentAlbumIds.intersect(albumsToKeep))

                // Update UI state
                val updatedPhotos = _photos.value.filterNot { it.isSelected }
                _photos.value = updatedPhotos
                _selectedCount.value = 0
                _state.value = PhotoManagerState.Success("Removed ${selectedPhotos.size} photos")

            } catch (e: Exception) {
                _state.value = PhotoManagerState.Error("Failed to remove photos: ${e.message}")
            }
        }
    }

    private fun clearSelection() {
        val currentPhotos = _photos.value.map { it.copy(isSelected = false) }
        _photos.value = currentPhotos
        _selectedCount.value = 0
    }

    fun saveVirtualAlbums() {
        viewModelScope.launch {
            try {
                val albums = _virtualAlbums.value
                Log.d(TAG, "Saving ${albums.size} virtual albums")

                // Save albums to preferences
                val jsonArray = JSONArray()
                albums.forEach { album ->
                    val albumJson = JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("photoUris", JSONArray(album.photoUris))
                        put("dateCreated", album.dateCreated)
                        put("isSelected", album.isSelected)
                    }
                    jsonArray.put(albumJson)
                }
                preferences.setString("virtual_albums", jsonArray.toString())
                Log.d(TAG, "Successfully saved ${albums.size} albums to preferences")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving virtual albums", e)
            }
        }
    }

    fun getDefaultAlbumCount(): Int {
        return virtualAlbums.value.count { it.name.startsWith("Default Album") }
    }

    private suspend fun saveVirtualAlbums(albums: List<VirtualAlbum>) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Saving ${albums.size} virtual albums")

                val jsonArray = JSONArray()
                albums.forEach { album ->
                    val albumJson = JSONObject().apply {
                        put("id", album.id)
                        put("name", album.name)
                        put("photoUris", JSONArray(album.photoUris))
                        put("dateCreated", album.dateCreated)
                        put("isSelected", album.isSelected)
                    }
                    jsonArray.put(albumJson)
                }
                preferences.setString("virtual_albums", jsonArray.toString())
                Log.d(TAG, "Successfully saved ${albums.size} albums to preferences")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving virtual albums", e)
            }
        }
    }

    fun toggleVirtualAlbumSelection(albumId: String) {
        val currentAlbums = _virtualAlbums.value.toMutableList()
        val index = currentAlbums.indexOfFirst { it.id == albumId }
        if (index != -1) {
            val newState = !currentAlbums[index].isSelected
            currentAlbums[index] = currentAlbums[index].copy(isSelected = newState)
            _virtualAlbums.value = currentAlbums

            // Immediately sync with repository
            viewModelScope.launch {
                photoRepository.syncVirtualAlbums(currentAlbums)
                saveVirtualAlbums(currentAlbums)

                // Just reload photos - let observers handle the display update
                photoRepository.loadPhotos()?.let { photos ->
                    // Update state to trigger observers
                    _state.value = PhotoManagerState.Success("Album selection updated")
                } ?: run {
                    // No photos available (no albums selected)
                    _state.value = PhotoManagerState.Empty
                }
            }

            Log.d(TAG, "Album selection toggled: $albumId, new state: $newState")
        }
    }

    fun reloadState() {
        loadInitialState()
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            Log.d(TAG, "ViewModel being cleared, saving ${_virtualAlbums.value.size} albums")
            saveVirtualAlbums(_virtualAlbums.value)
        }
    }

    fun debugPrintAllAlbums() {
        viewModelScope.launch {
            Log.d(TAG, "=== All Albums in Repository ===")
            photoRepository.getAllAlbums().forEach { album ->
                Log.d(TAG, """
                Album:
                • ID: ${album.id}
                • Name: ${album.name}
                • Photos: ${album.photoUris.size}
                • Selected: ${album.isSelected}
            """.trimIndent())
            }
            Log.d(TAG, "=== End Album List ===")
        }
    }
}