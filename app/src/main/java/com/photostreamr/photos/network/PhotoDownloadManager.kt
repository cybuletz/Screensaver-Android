package com.photostreamr.photos.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import com.photostreamr.PhotoRepository
import com.photostreamr.models.MediaItem
import com.photostreamr.photos.VirtualAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoDownloadManager @Inject constructor(
    private val context: Context,
    private val networkPhotoManager: NetworkPhotoManager,
    private val photoRepository: PhotoRepository,
    private val workManager: WorkManager
) {
    companion object {
        private const val TAG = "PhotoDownloadManager"
        private const val MAX_CONCURRENT_DOWNLOADS = 4

        // WorkManager tag constants
        const val WORK_TAG_DOWNLOAD = "network_download"
        const val WORK_DATA_SERVER = "server_data"
        const val WORK_DATA_RESOURCE = "resource_data"
        const val WORK_DATA_ALBUM_ID = "album_id"
        const val WORK_DATA_PHOTO_ID = "photo_id"

        // Shared preferences keys for download state
        private const val PREFS_NAME = "network_download_state"
        private const val KEY_CURRENT_DOWNLOADS = "current_downloads"
        private const val KEY_COMPLETED_DOWNLOADS = "completed_downloads"
        private const val KEY_FAILED_DOWNLOADS = "failed_downloads"
        private const val KEY_TOTAL_DOWNLOADS = "total_downloads"
        private const val KEY_ACTIVE_ALBUM_ID = "active_album_id"
    }

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)

    // Track ongoing operations
    private val activeDownloads = ConcurrentHashMap<String, Job>()

    // Store download state info for persistence
    private val _downloadState = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadState: StateFlow<Map<String, DownloadState>> = _downloadState.asStateFlow()

    // Progress tracking
    private val _downloadProgress = MutableLiveData<DownloadProgress>()
    val downloadProgress: LiveData<DownloadProgress> = _downloadProgress

    private val completedDownloads = AtomicInteger(0)
    private val failedDownloads = AtomicInteger(0)
    private val totalDownloads = AtomicInteger(0)
    private var currentAlbumId: String? = null

    data class DownloadProgress(
        val total: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val isActive: Boolean = false
    )

    data class DownloadState(
        val albumId: String,
        val resource: NetworkResource,
        val status: Status,
        val progress: Float = 0f
    ) {
        enum class Status {
            PENDING,
            DOWNLOADING,
            COMPLETED,
            FAILED
        }
    }

    init {
        // Load saved state on initialization
        loadSavedState()

        // Setup WorkManager observer
        observeWorkManager()
    }

    private fun observeWorkManager() {
        workManager
            .getWorkInfosByTagLiveData(WORK_TAG_DOWNLOAD)
            .observeForever { workInfos ->
                if (workInfos == null || workInfos.isEmpty()) return@observeForever

                var completed = 0
                var failed = 0
                var total = workInfos.size

                workInfos.forEach { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> completed++
                        WorkInfo.State.FAILED -> failed++
                        WorkInfo.State.CANCELLED -> total--
                        else -> { /* Other states don't affect our counts */ }
                    }
                }

                // Update our progress counters with work manager data
                if (completedDownloads.get() < completed) {
                    completedDownloads.set(completed)
                }
                if (failedDownloads.get() < failed) {
                    failedDownloads.set(failed)
                }
                if (totalDownloads.get() < total) {
                    totalDownloads.set(total)
                }

                // Post updated progress
                updateProgress(workInfos.any { it.state == WorkInfo.State.RUNNING })
            }
    }

    private fun loadSavedState() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load counters
        completedDownloads.set(prefs.getInt(KEY_COMPLETED_DOWNLOADS, 0))
        failedDownloads.set(prefs.getInt(KEY_FAILED_DOWNLOADS, 0))
        totalDownloads.set(prefs.getInt(KEY_TOTAL_DOWNLOADS, 0))
        currentAlbumId = prefs.getString(KEY_ACTIVE_ALBUM_ID, null)

        // Load download state
        val downloadsJson = prefs.getString(KEY_CURRENT_DOWNLOADS, null)
        if (downloadsJson != null) {
            try {
                val downloadStates = mutableMapOf<String, DownloadState>()
                val jsonArray = JSONArray(downloadsJson)

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val resourceObj = item.getJSONObject("resource")
                    val serverObj = resourceObj.getJSONObject("server")

                    // Recreate server
                    val server = NetworkServer(
                        id = serverObj.getString("id"),
                        name = serverObj.getString("name"),
                        address = serverObj.getString("address"),
                        username = if (serverObj.has("username")) serverObj.getString("username") else null,
                        password = if (serverObj.has("password")) serverObj.getString("password") else null,
                        isManual = serverObj.getBoolean("isManual")
                    )

                    // Recreate resource
                    val resource = NetworkResource(
                        server = server,
                        path = resourceObj.getString("path"),
                        name = resourceObj.getString("name"),
                        isDirectory = resourceObj.getBoolean("isDirectory"),
                        isImage = resourceObj.getBoolean("isImage"),
                        size = resourceObj.getLong("size"),
                        lastModified = resourceObj.getLong("lastModified")
                    )

                    // Recreate download state
                    val downloadState = DownloadState(
                        albumId = item.getString("albumId"),
                        resource = resource,
                        status = DownloadState.Status.valueOf(item.getString("status")),
                        progress = item.getDouble("progress").toFloat()
                    )

                    // Add to our map
                    val photoId = "${resource.server.id}_${resource.path.hashCode()}"
                    downloadStates[photoId] = downloadState
                }

                // Update state flow
                _downloadState.value = downloadStates

            } catch (e: Exception) {
                Log.e(TAG, "Error loading download state", e)
            }
        }

        // If we have active downloads, update the progress
        if (totalDownloads.get() > 0) {
            updateProgress(true)
        }
    }

    private fun saveState() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Save counters
        editor.putInt(KEY_COMPLETED_DOWNLOADS, completedDownloads.get())
        editor.putInt(KEY_FAILED_DOWNLOADS, failedDownloads.get())
        editor.putInt(KEY_TOTAL_DOWNLOADS, totalDownloads.get())
        currentAlbumId?.let {
            editor.putString(KEY_ACTIVE_ALBUM_ID, it)
        } ?: editor.remove(KEY_ACTIVE_ALBUM_ID)

        // Save download states
        val downloadStates = _downloadState.value
        if (downloadStates.isNotEmpty()) {
            val jsonArray = JSONArray()

            downloadStates.forEach { (_, state) ->
                try {
                    // Create server JSON
                    val serverObj = JSONObject().apply {
                        put("id", state.resource.server.id)
                        put("name", state.resource.server.name)
                        put("address", state.resource.server.address)
                        state.resource.server.username?.let { put("username", it) }
                        state.resource.server.password?.let { put("password", it) }
                        put("isManual", state.resource.server.isManual)
                    }

                    // Create resource JSON
                    val resourceObj = JSONObject().apply {
                        put("server", serverObj)
                        put("path", state.resource.path)
                        put("name", state.resource.name)
                        put("isDirectory", state.resource.isDirectory)
                        put("isImage", state.resource.isImage)
                        put("size", state.resource.size)
                        put("lastModified", state.resource.lastModified)
                    }

                    // Create download state JSON
                    val stateObj = JSONObject().apply {
                        put("albumId", state.albumId)
                        put("resource", resourceObj)
                        put("status", state.status.name)
                        put("progress", state.progress)
                    }

                    jsonArray.put(stateObj)
                } catch (e: Exception) {
                    Log.e(TAG, "Error serializing download state", e)
                }
            }

            editor.putString(KEY_CURRENT_DOWNLOADS, jsonArray.toString())
        } else {
            editor.remove(KEY_CURRENT_DOWNLOADS)
        }

        // Commit changes
        editor.apply()
    }

    fun downloadFolderContents(folder: NetworkResource, addToAlbums: Boolean = true) {
        downloadScope.launch {
            try {
                // Reset counters
                completedDownloads.set(0)
                failedDownloads.set(0)
                totalDownloads.set(0)

                // Clear existing download states
                _downloadState.value = emptyMap()

                // Create a virtual album
                val albumId = "network_${System.currentTimeMillis()}"
                currentAlbumId = albumId

                val virtualAlbum = VirtualAlbum(
                    id = albumId,
                    name = "Network: ${folder.name}",
                    photoUris = mutableListOf(),
                    dateCreated = System.currentTimeMillis(),
                    isSelected = true // Auto-select for immediate viewing
                )

                // Add empty album to repository to make it available immediately
                if (addToAlbums) {
                    photoRepository.addVirtualAlbum(virtualAlbum)
                }

                // Update progress
                updateProgress(true)

                // Save initial state
                saveState()

                // Scan folder
                scanAndDownloadFolder(folder, albumId)
            } catch (e: Exception) {
                Log.e(TAG, "Error in folder download", e)
            }
        }
    }

    private suspend fun scanAndDownloadFolder(folder: NetworkResource, albumId: String) {
        try {
            val result = networkPhotoManager.browseNetworkPath(folder.server, folder.path)
            if (result.isFailure) {
                Log.e(TAG, "Failed to browse folder: ${folder.path}", result.exceptionOrNull())
                return
            }

            val resources = result.getOrDefault(emptyList())
            val images = resources.filter { it.isImage }
            val subfolders = resources.filter { it.isDirectory }

            // Update total count
            totalDownloads.addAndGet(images.size)

            // Update and save state
            saveState()

            // Start downloading images using WorkManager for background processing
            for (image in images) {
                enqueuePhotoDownload(image, albumId)
            }

            // Process subfolders recursively
            for (subfolder in subfolders) {
                scanAndDownloadFolder(subfolder, albumId)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error scanning folder: ${folder.path}", e)
        }
    }

    private fun enqueuePhotoDownload(resource: NetworkResource, albumId: String) {
        val photoId = "${resource.server.id}_${resource.path.hashCode()}"

        // Skip if already downloading or completed
        val currentState = _downloadState.value[photoId]
        if (currentState?.status == DownloadState.Status.DOWNLOADING ||
            currentState?.status == DownloadState.Status.COMPLETED) {
            return
        }

        // Update download state to pending
        val newState = _downloadState.value.toMutableMap()
        newState[photoId] = DownloadState(
            albumId = albumId,
            resource = resource,
            status = DownloadState.Status.PENDING
        )
        _downloadState.value = newState

        // Serialize the resource and server data
        val serverJson = JSONObject().apply {
            put("id", resource.server.id)
            put("name", resource.server.name)
            put("address", resource.server.address)
            resource.server.username?.let { put("username", it) }
            resource.server.password?.let { put("password", it) }
            put("isManual", resource.server.isManual)
        }

        val resourceJson = JSONObject().apply {
            put("server", serverJson.toString())
            put("path", resource.path)
            put("name", resource.name)
            put("isDirectory", resource.isDirectory)
            put("isImage", resource.isImage)
            put("size", resource.size)
            put("lastModified", resource.lastModified)
        }

        // Create work request for background download
        val workData = workDataOf(
            WORK_DATA_SERVER to serverJson.toString(),
            WORK_DATA_RESOURCE to resourceJson.toString(),
            WORK_DATA_ALBUM_ID to albumId,
            WORK_DATA_PHOTO_ID to photoId
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PhotoDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workData)
            .addTag(WORK_TAG_DOWNLOAD)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                30000, // 30 seconds in milliseconds
                TimeUnit.MILLISECONDS
            )
            .build()

        // Enqueue the work
        workManager.enqueueUniqueWork(
            "download_$photoId",
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "Enqueued background download for ${resource.name}")
    }

    suspend fun continueDownloads() {
        // Get saved album ID
        val albumId = currentAlbumId ?: return

        // Check if we have pending downloads
        val pendingDownloads = _downloadState.value.filter {
            it.value.status == DownloadState.Status.PENDING || it.value.status == DownloadState.Status.DOWNLOADING
        }

        if (pendingDownloads.isEmpty()) {
            return
        }

        // Re-enqueue all pending downloads
        pendingDownloads.forEach { (_, state) ->
            enqueuePhotoDownload(state.resource, albumId)
        }

        // Update progress
        updateProgress(true)
    }

    private suspend fun downloadPhotoDirectly(resource: NetworkResource, albumId: String) {
        val photoId = "${resource.server.id}_${resource.path.hashCode()}"

        // Skip if already downloading
        if (activeDownloads.containsKey(photoId)) {
            return
        }

        // Update download state
        val newState = _downloadState.value.toMutableMap()
        newState[photoId] = DownloadState(
            albumId = albumId,
            resource = resource,
            status = DownloadState.Status.DOWNLOADING
        )
        _downloadState.value = newState

        val job = downloadScope.launch {
            try {
                // Use the new method to download and cache the photo
                val uri = networkPhotoManager.downloadAndCachePhoto(resource)
                if (uri != null) {
                    // Create media item with the cached URI
                    val mediaItem = MediaItem(
                        id = photoId,
                        albumId = albumId,
                        baseUrl = uri,
                        mimeType = getMimeType(resource.name),
                        width = 0,
                        height = 0,
                        description = resource.name,
                        createdAt = System.currentTimeMillis(),
                        loadState = MediaItem.LoadState.IDLE
                    )

                    // Add to repository immediately
                    withContext(Dispatchers.Main) {
                        photoRepository.addPhotos(listOf(mediaItem), PhotoRepository.PhotoAddMode.MERGE)

                        // Update the virtual album
                        photoRepository.getAllAlbums().find { it.id == albumId }?.let { album ->
                            val updatedUris = album.photoUris.toMutableList()
                            updatedUris.add(uri)

                            val updatedAlbum = album.copy(photoUris = updatedUris)

                            val allAlbums = photoRepository.getAllAlbums().toMutableList()
                            val index = allAlbums.indexOfFirst { it.id == albumId }
                            if (index >= 0) {
                                allAlbums[index] = updatedAlbum
                                photoRepository.syncVirtualAlbums(allAlbums)
                            }
                        }

                        // Update completed count and post progress
                        completedDownloads.incrementAndGet()

                        // Update download state
                        val updatedState = _downloadState.value.toMutableMap()
                        updatedState[photoId] = DownloadState(
                            albumId = albumId,
                            resource = resource,
                            status = DownloadState.Status.COMPLETED,
                            progress = 1.0f
                        )
                        _downloadState.value = updatedState

                        // Save state
                        saveState()

                        updateProgress(true)
                        Log.d(TAG, "Added optimized photo to album: ${resource.name}, completed: ${completedDownloads.get()}")
                    }
                } else {
                    // Failed
                    failedDownloads.incrementAndGet()

                    // Update download state
                    val updatedState = _downloadState.value.toMutableMap()
                    updatedState[photoId] = DownloadState(
                        albumId = albumId,
                        resource = resource,
                        status = DownloadState.Status.FAILED,
                        progress = 0f
                    )
                    _downloadState.value = updatedState

                    // Save state
                    saveState()

                    updateProgress(true)
                    Log.e(TAG, "Failed to download/cache photo: ${resource.name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading photo: ${resource.name}", e)
                failedDownloads.incrementAndGet()

                // Update download state
                val updatedState = _downloadState.value.toMutableMap()
                updatedState[photoId] = DownloadState(
                    albumId = albumId,
                    resource = resource,
                    status = DownloadState.Status.FAILED,
                    progress = 0f
                )
                _downloadState.value = updatedState

                // Save state
                saveState()

                updateProgress(true)
            } finally {
                activeDownloads.remove(photoId)

                // Check if we're done
                if (activeDownloads.isEmpty() &&
                    completedDownloads.get() + failedDownloads.get() >= totalDownloads.get()
                ) {
                    updateProgress(false)

                    // When all downloads are done, clear the active album ID
                    if (completedDownloads.get() + failedDownloads.get() >= totalDownloads.get()) {
                        currentAlbumId = null
                        saveState()
                    }
                }
            }
        }

        // Track active download
        activeDownloads[photoId] = job
    }

    private fun updateProgress(isActive: Boolean) {
        _downloadProgress.postValue(
            DownloadProgress(
                total = totalDownloads.get(),
                completed = completedDownloads.get(),
                failed = failedDownloads.get(),
                isActive = isActive
            )
        )
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg" // Default to JPEG
        }
    }

    fun cancelAllDownloads() {
        // Cancel all jobs
        activeDownloads.forEach { (_, job) ->
            job.cancel()
        }
        activeDownloads.clear()

        // Cancel all WorkManager jobs
        workManager.cancelAllWorkByTag(WORK_TAG_DOWNLOAD)

        // Clear all download states
        _downloadState.value = emptyMap()

        // Reset counters
        completedDownloads.set(0)
        failedDownloads.set(0)
        totalDownloads.set(0)
        currentAlbumId = null

        // Save cleared state
        saveState()

        // Update progress
        updateProgress(false)
    }

    /**
     * Resume any pending downloads from a previous session
     */
    fun resumeDownloads() {
        downloadScope.launch {
            continueDownloads()
        }
    }
}