package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.ListMediaItemsRequest
import com.google.photos.types.proto.MediaItem
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class GooglePhotosManager(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "GooglePhotosManager"
    }

    fun initialize(account: GoogleSignInAccount) {
        try {
            Log.d(TAG, "Initializing with account: ${account.email}")

            val token = account.idToken
            if (token == null) {
                Log.e(TAG, "ID token is null")
                throw IllegalStateException("No ID token available")
            }

            val credentials = GoogleCredentials.create(
                AccessToken(
                    token,
                    Date(System.currentTimeMillis() + 3600000) // Token expires in 1 hour
                )
            ).createScoped(listOf("https://www.googleapis.com/auth/photoslibrary.readonly"))

            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            photosLibraryClient = PhotosLibraryClient.initialize(settings)
            isInitialized = true
            Log.d(TAG, "PhotosLibrary client initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PhotosLibrary client", e)
            throw e
        }
    }

    suspend fun getRandomPhotos(count: Int = 1): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized || photosLibraryClient == null) {
                Log.e(TAG, "PhotosLibrary client not initialized")
                return@withContext emptyList()
            }

            Log.d(TAG, "Getting random photos, maxResults: $count")

            val request = ListMediaItemsRequest.newBuilder()
                .setPageSize(50)
                .build()

            val response = photosLibraryClient?.listMediaItems(request)
            val mediaItems: List<MediaItem> = response?.mediaItemsList ?: emptyList()

            if (mediaItems.isEmpty()) {
                Log.d(TAG, "No media items found in Google Photos")
                return@withContext emptyList()
            }

            return@withContext mediaItems
                .shuffled()
                .take(count)
                .map { mediaItem: MediaItem -> "${mediaItem.baseUrl}=w1920-h1080" }
                .also { urls: List<String> ->
                    Log.d(TAG, "Retrieved ${urls.size} random photos")
                    urls.forEach { url: String ->
                        Log.d(TAG, "Photo URL: $url")
                    }
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting random photos", e)
            throw e
        }
    }

    fun cleanup() {
        try {
            photosLibraryClient?.shutdown()
            photosLibraryClient = null
            isInitialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}