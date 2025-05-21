package com.photostreamr.photos.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photostreamr.PhotoRepository
import com.photostreamr.models.MediaItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhotoDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "PhotoDownloadWorker"
    }

    // Entry point for Hilt to provide dependencies
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PhotoDownloadWorkerEntryPoint {
        fun networkPhotoManager(): NetworkPhotoManager
        fun photoRepository(): PhotoRepository
    }

    // Get dependencies from Hilt
    private val entryPoint = EntryPointAccessors.fromApplication(
        appContext.applicationContext,
        PhotoDownloadWorkerEntryPoint::class.java
    )

    private val networkPhotoManager = entryPoint.networkPhotoManager()
    private val photoRepository = entryPoint.photoRepository()

    override suspend fun doWork(): Result {
        try {
            // Get input data
            val serverJson = inputData.getString(PhotoDownloadManager.WORK_DATA_SERVER) ?: return Result.failure()
            val resourceJson = inputData.getString(PhotoDownloadManager.WORK_DATA_RESOURCE) ?: return Result.failure()
            val albumId = inputData.getString(PhotoDownloadManager.WORK_DATA_ALBUM_ID) ?: return Result.failure()
            val photoId = inputData.getString(PhotoDownloadManager.WORK_DATA_PHOTO_ID) ?: return Result.failure()

            // Parse server data
            val serverObj = JSONObject(serverJson)
            val server = NetworkServer(
                id = serverObj.getString("id"),
                name = serverObj.getString("name"),
                address = serverObj.getString("address"),
                username = if (serverObj.has("username")) serverObj.getString("username") else null,
                password = if (serverObj.has("password")) serverObj.getString("password") else null,
                isManual = serverObj.getBoolean("isManual")
            )

            // Parse resource data
            val resourceObj = JSONObject(resourceJson)
            val resource = NetworkResource(
                server = server,
                path = resourceObj.getString("path"),
                name = resourceObj.getString("name"),
                isDirectory = resourceObj.getBoolean("isDirectory"),
                isImage = resourceObj.getBoolean("isImage"),
                size = resourceObj.getLong("size"),
                lastModified = resourceObj.getLong("lastModified")
            )

            // Download the photo
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

                // Add to repository
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

                    Log.d(TAG, "Successfully downloaded and added photo: ${resource.name}")
                }

                return Result.success()
            } else {
                Log.e(TAG, "Failed to download photo: ${resource.name}")
                return Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in download worker", e)
            return Result.failure()
        }
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
}