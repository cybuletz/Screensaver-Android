package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.auth.oauth2.UserCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosHelper private constructor() {
    private var photosLibraryClient: PhotosLibraryClient? = null

    companion object {
        private const val TAG = "PhotosHelper"

        @Volatile
        private var instance: PhotosHelper? = null

        fun getInstance(): PhotosHelper =
            instance ?: synchronized(this) {
                instance ?: PhotosHelper().also { instance = it }
            }
    }

    // Initialize the Photos Library client with the Google account
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

    // Get photos from Google Photos
    suspend fun getPhotos(maxResults: Int = 100): List<PhotoItem> = withContext(Dispatchers.IO) {
        try {
            photosLibraryClient?.let { client ->
                val filters = Filters.newBuilder().build()
                client.searchMediaItems(filters)
                    .iterateAll()
                    .take(maxResults)
                    .map { mediaItem ->
                        PhotoItem(
                            id = mediaItem.id,
                            url = mediaItem.baseUrl,
                            filename = mediaItem.filename
                        )
                    }
                    .toList()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos", e)
            emptyList()
        }
    }
}

// Data class to hold photo information
data class PhotoItem(
    val id: String,
    val url: String,
    val filename: String
)