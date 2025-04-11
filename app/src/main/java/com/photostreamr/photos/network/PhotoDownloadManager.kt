package com.photostreamr.photos.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.photostreamr.PhotoRepository
import com.photostreamr.models.MediaItem
import com.photostreamr.photos.VirtualAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoDownloadManager @Inject constructor(
    private val context: Context,
    private val networkPhotoManager: NetworkPhotoManager,
    private val photoRepository: PhotoRepository
) {
    companion object {
        private const val TAG = "PhotoDownloadManager"
        private const val MAX_CONCURRENT_DOWNLOADS = 4
    }

    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS)

    // Track ongoing operations
    private val activeDownloads = ConcurrentHashMap<String, Job>()

    // Progress tracking
    private val _downloadProgress = MutableLiveData<DownloadProgress>()
    val downloadProgress: LiveData<DownloadProgress> = _downloadProgress

    private val completedDownloads = AtomicInteger(0)
    private val failedDownloads = AtomicInteger(0)
    private val totalDownloads = AtomicInteger(0)

    data class DownloadProgress(
        val total: Int = 0,
        val completed: Int = 0,
        val failed: Int = 0,
        val isActive: Boolean = false
    )

    fun downloadFolderContents(folder: NetworkResource, addToAlbums: Boolean = true) {
        downloadScope.launch {
            try {
                // Reset counters
                completedDownloads.set(0)
                failedDownloads.set(0)
                totalDownloads.set(0)

                // Create a virtual album
                val albumId = "network_${System.currentTimeMillis()}"
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

            // Start downloading images
            for (image in images) {
                downloadPhoto(image, albumId)
            }

            // Process subfolders recursively
            for (subfolder in subfolders) {
                scanAndDownloadFolder(subfolder, albumId)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error scanning folder: ${folder.path}", e)
        }
    }

    private suspend fun downloadPhoto(resource: NetworkResource, albumId: String) {
        val photoId = "${resource.server.id}_${resource.path.hashCode()}"

        // Skip if already downloading
        if (activeDownloads.containsKey(photoId)) {
            return
        }

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
                    }

                    // Update counts
                    completedDownloads.incrementAndGet()
                    Log.d(TAG, "Added optimized photo to album: ${resource.name}")
                } else {
                    // Failed
                    failedDownloads.incrementAndGet()
                    Log.e(TAG, "Failed to download/cache photo: ${resource.name}")
                }

                // Update progress
                updateProgress(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading photo: ${resource.name}", e)
                failedDownloads.incrementAndGet()
                updateProgress(true)
            } finally {
                activeDownloads.remove(photoId)

                // Check if we're done
                if (activeDownloads.isEmpty() &&
                    completedDownloads.get() + failedDownloads.get() >= totalDownloads.get()) {
                    updateProgress(false)
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

        // Update progress
        updateProgress(false)
    }
}