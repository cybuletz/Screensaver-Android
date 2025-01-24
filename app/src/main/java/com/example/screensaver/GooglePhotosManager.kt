package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.ListMediaItemsRequest
import com.google.photos.types.proto.MediaItem
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
        private val REQUIRED_SCOPES = listOf(
            "https://www.googleapis.com/auth/photoslibrary.readonly",
            "https://www.googleapis.com/auth/photoslibrary",
            "https://www.googleapis.com/auth/photos.readonly"
        )
    }

    @Throws(IllegalStateException::class)
    fun initialize(account: GoogleSignInAccount) {
        try {
            Log.d(TAG, "Initializing with account: ${account.email}")

            // Check if we have a valid ID token
            val token = account.idToken
            if (token.isNullOrEmpty()) {
                Log.e(TAG, "ID token is null or empty")
                throw IllegalStateException("No valid ID token available")
            }

            // Create credentials with all required scopes
            val credentials = GoogleCredentials.create(
                AccessToken(
                    token,
                    Date(System.currentTimeMillis() + 3600000) // Token expires in 1 hour
                )
            ).createScoped(REQUIRED_SCOPES)

            // Build settings with credentials provider
            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            // Initialize the client
            photosLibraryClient = PhotosLibraryClient.initialize(settings).also {
                isInitialized = true
                Log.d(TAG, "PhotosLibrary client initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PhotosLibrary client", e)
            isInitialized = false
            photosLibraryClient = null
            throw IllegalStateException("Failed to initialize Photos API: ${e.message}", e)
        }
    }

    suspend fun getRandomPhotos(count: Int = 1): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized || photosLibraryClient == null) {
                Log.e(TAG, "PhotosLibrary client not initialized")
                throw IllegalStateException("PhotosLibrary client not initialized")
            }

            Log.d(TAG, "Getting random photos, maxResults: $count")

            val request = ListMediaItemsRequest.newBuilder()
                .setPageSize(50)  // Fetch 50 items to get a good random sample
                .build()

            val mediaItems = mutableListOf<MediaItem>()

            // Safely get media items
            photosLibraryClient?.listMediaItems(request)?.iterateAll()?.forEach { item ->
                mediaItems.add(item)
                if (mediaItems.size >= 50) return@forEach  // Stop after 50 items
            }

            if (mediaItems.isEmpty()) {
                Log.d(TAG, "No media items found in Google Photos")
                return@withContext emptyList()
            }

            // Get random photos and build URLs
            return@withContext mediaItems
                .shuffled()
                .take(count.coerceAtMost(mediaItems.size))
                .map { mediaItem -> "${mediaItem.baseUrl}=w1920-h1080" }
                .also { urls ->
                    Log.d(TAG, "Retrieved ${urls.size} random photos")
                    urls.forEach { url ->
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
            Log.d(TAG, "PhotosLibrary client cleaned up successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    fun isClientInitialized(): Boolean = isInitialized && photosLibraryClient != null
}