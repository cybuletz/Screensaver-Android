package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

private const val TAG = "GooglePhotosManager"

interface GooglePhotosApiService {
    @GET("v1/mediaItems")
    suspend fun listMediaItems(
        @Header("Authorization") token: String,
        @Query("pageSize") pageSize: Int
    ): MediaItemsResponse
}

class GooglePhotosManager(private val context: Context) {
    private lateinit var photosLibraryClient: PhotosLibrary
    private var isInitialized = false

    companion object {
        private const val TAG = "GooglePhotosManager"
    }

    fun initialize(account: GoogleSignInAccount) {
        try {
            Log.d(TAG, "Initializing with account: ${account.email}")

            // Get token
            val token = account.idToken
            if (token == null) {
                Log.e(TAG, "ID token is null")
                throw IllegalStateException("No ID token available")
            }

            // Create credentials
            val credentials = GoogleCredentials.create(
                AccessToken(
                    token,
                    null // No expiration time available from GoogleSignInAccount
                )
            ).createScoped(listOf(
                "https://www.googleapis.com/auth/photoslibrary.readonly",
                "https://www.googleapis.com/auth/photos.readonly"
            ))

            val requestInitializer: HttpRequestInitializer = HttpCredentialsAdapter(credentials)

            photosLibraryClient = PhotosLibrary.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                requestInitializer
            )
                .setApplicationName("Screensaver")
                .build()

            isInitialized = true
            Log.d(TAG, "PhotosLibrary client initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PhotosLibrary client", e)
            throw e
        }
    }

    suspend fun getRandomPhotos(count: Int = 1): List<String> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.e(TAG, "PhotosLibrary client not initialized")
                return@withContext emptyList()
            }

            Log.d(TAG, "Getting random photos, maxResults: $count")

            val request = ListMediaItemsRequest()
                .setPageSize(50)

            val response = photosLibraryClient.mediaItems()
                .list()
                .setPageSize(50)
                .execute()

            val mediaItems = response.mediaItems
            if (mediaItems == null || mediaItems.isEmpty()) {
                Log.d(TAG, "No media items found in Google Photos")
                return@withContext emptyList()
            }

            return@withContext mediaItems
                .shuffled()
                .take(count)
                .map { "${it.baseUrl}=w1920-h1080" } // Add size parameters
                .also { urls ->
                    Log.d(TAG, "Retrieved ${urls.size} random photos")
                    urls.forEach { url -> Log.d(TAG, "Photo URL: $url") }
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error getting random photos", e)
            throw e
        }
    }
}