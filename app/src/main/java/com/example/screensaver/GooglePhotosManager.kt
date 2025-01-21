package com.example.screensaver

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.UserCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class GooglePhotosManager(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null

    companion object {
        private const val TAG = "GooglePhotosManager"
    }

    fun initialize(account: GoogleSignInAccount) {
        try {
            val token = AccessToken.newBuilder()
                .setTokenValue(account.idToken ?: "")
                .setExpirationTime(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
                .build()

            val credentials = UserCredentials.newBuilder()
                .setAccessToken(token)
                .build()

            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
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
                // Get media items without filters
                val mediaItems = mutableListOf<String>()

                client.listMediaItems()
                    .iterateAll()
                    .take(maxResults)
                    .mapNotNullTo(mediaItems) { item ->
                        item.baseUrl
                    }

                mediaItems.shuffled()
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos: ${e.message}", e)
            emptyList()
        }
    }
}