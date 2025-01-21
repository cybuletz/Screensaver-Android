package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.UserCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.internal.InternalPhotosLibraryClient.SearchMediaItemsPagedResponse
import com.google.photos.types.proto.Filters
import com.google.photos.types.proto.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GooglePhotosManager(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null

    companion object {
        private const val TAG = "GooglePhotosManager"
    }

    fun initialize(account: GoogleSignInAccount) {
        try {
            val credentials = UserCredentials.newBuilder()
                .setAccessToken(AccessToken.newBuilder()
                    .setTokenValue(account.idToken ?: "")
                    .build())
                .build()

            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            photosLibraryClient = PhotosLibraryClient.initialize(settings)
            Log.d(TAG, "PhotosLibraryClient initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PhotosLibraryClient", e)
        }
    }

    fun isAuthenticated() = photosLibraryClient != null

    suspend fun getRandomPhotos(maxResults: Int = 50): List<String> = withContext(Dispatchers.IO) {
        try {
            photosLibraryClient?.let { client ->
                val filters = Filters.newBuilder().build()
                val response: SearchMediaItemsPagedResponse = client.searchMediaItems(filters)

                // Convert PagedResponse to List<MediaItem>
                val mediaItems = mutableListOf<MediaItem>()
                var count = 0

                for (item in response.iterateAll()) {
                    if (count >= maxResults) break
                    mediaItems.add(item)
                    count++
                }

                // Extract baseUrls and shuffle
                mediaItems.map { it.baseUrl }.shuffled()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos: ${e.message}", e)
            emptyList()
        }
    }
}