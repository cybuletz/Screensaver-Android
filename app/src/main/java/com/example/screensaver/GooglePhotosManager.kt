package com.example.screensaver

import android.content.Context
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.UserCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.types.proto.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GooglePhotosManager(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null

    companion object {
        private const val PREFS_NAME = "GooglePhotosPrefs"
        private const val ACCESS_TOKEN_KEY = "accessToken"
        private const val REFRESH_TOKEN_KEY = "refreshToken"

        // Replace these with your actual OAuth 2.0 credentials from Google Cloud Console
        private const val CLIENT_ID = "YOUR_CLIENT_ID"
        private const val CLIENT_SECRET = "YOUR_CLIENT_SECRET"
    }

    private fun getSharedPreferences() =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveTokens(accessToken: String, refreshToken: String) {
        getSharedPreferences().edit().apply {
            putString(ACCESS_TOKEN_KEY, accessToken)
            putString(REFRESH_TOKEN_KEY, refreshToken)
            apply()
        }
    }

    private fun createPhotosLibraryClient(accessToken: String, refreshToken: String): PhotosLibraryClient {
        val credentials = UserCredentials.newBuilder()
            .setClientId(CLIENT_ID)
            .setClientSecret(CLIENT_SECRET)
            .setAccessToken(accessToken)
            .setRefreshToken(refreshToken)
            .build()

        val settings = PhotosLibrarySettings.newBuilder()
            .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
            .build()

        return PhotosLibraryClient.initialize(settings)
    }

    suspend fun getRandomPhotos(count: Int = 10): List<String> = withContext(Dispatchers.IO) {
        val accessToken = getSharedPreferences().getString(ACCESS_TOKEN_KEY, null)
        val refreshToken = getSharedPreferences().getString(REFRESH_TOKEN_KEY, null)

        if (accessToken == null || refreshToken == null) {
            throw IllegalStateException("User not authenticated")
        }

        try {
            if (photosLibraryClient == null) {
                photosLibraryClient = createPhotosLibraryClient(accessToken, refreshToken)
            }

            val mediaItems = photosLibraryClient?.listMediaItems()?.iterateAll()?.toList()
                ?: emptyList<MediaItem>()

            return@withContext mediaItems
                .shuffled()
                .take(count)
                .map { it.baseUrl }
        } catch (e: Exception) {
            throw e
        }
    }

    fun isAuthenticated(): Boolean {
        val prefs = getSharedPreferences()
        return !prefs.getString(ACCESS_TOKEN_KEY, null).isNullOrEmpty() &&
                !prefs.getString(REFRESH_TOKEN_KEY, null).isNullOrEmpty()
    }

    fun clearAuth() {
        getSharedPreferences().edit().clear().apply()
        photosLibraryClient?.close()
        photosLibraryClient = null
    }
}