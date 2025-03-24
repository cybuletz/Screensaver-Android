package com.example.screensaver.data

import android.content.Context
import android.util.Log
import com.example.screensaver.PhotoRepository
import com.example.screensaver.models.MediaItem
import com.example.screensaver.photos.VirtualAlbum
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class PhotoMigrationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository,
    private val storageCoordinator: PhotoStorageCoordinator
) {
    suspend fun migrateToCoordinator() {
        try {
            Log.d(TAG, "Starting migration to PhotoStorageCoordinator")

            // Get all existing photos from repository
            val existingPhotos = photoRepository.getAllPhotos()
            if (existingPhotos.isNotEmpty()) {
                // Add to coordinator
                storageCoordinator.addPhotos(existingPhotos)
                Log.d(TAG, "Migrated ${existingPhotos.size} photos to coordinator")
            }

            // Get all virtual albums
            val existingAlbums = photoRepository.getAllAlbums()
            if (existingAlbums.isNotEmpty()) {
                storageCoordinator.addVirtualAlbums(existingAlbums)
                Log.d(TAG, "Migrated ${existingAlbums.size} virtual albums to coordinator")
            }

            Log.d(TAG, "Migration completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during migration to coordinator", e)
            throw e
        }
    }

    companion object {
        private const val TAG = "PhotoMigrationManager"
    }
}