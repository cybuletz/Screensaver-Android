package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.photoslibrary.v1.PhotosLibrary
import com.google.api.services.photoslibrary.v1.PhotosLibraryScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

class GooglePhotosManager(private val context: Context) {
    private lateinit var photosLibraryClient: PhotosLibrary
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
            ).createScoped(listOf(PhotosLibraryScopes.PHOTOSLIBRARY_READONLY))

            val requestInitializer: HttpRequestInitializer = HttpCredentialsAdapter(credentials)

            photosLibraryClient = PhotosLibrary.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
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
                .map { mediaItem -> "${mediaItem.baseUrl}=w1920-h1080" }
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