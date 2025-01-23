package com.example.screensaver.utils

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel  // Add this import
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class PhotoManager private constructor(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val photoUrls = mutableListOf<String>()
    private var currentPhotoIndex = 0
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "PhotoManager"
        private const val PHOTO_QUALITY = "=w2560-h1440" // 2K quality
        @Volatile private var instance: PhotoManager? = null

        fun getInstance(context: Context): PhotoManager {
            return instance ?: synchronized(this) {
                instance ?: PhotoManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun loadPhotos() {
        coroutineScope.launch {
            try {
                if (photoUrls.isNotEmpty()) {
                    return@launch
                }

                val prefs = context.getSharedPreferences("screensaver_prefs", Context.MODE_PRIVATE)
                val selectedAlbums = prefs.getStringSet("selected_albums", emptySet()) ?: emptySet()
                if (selectedAlbums.isEmpty()) {
                    Log.e(TAG, "No albums selected")
                    return@launch
                }

                val credentials = getOrRefreshCredentials()
                val settings = PhotosLibrarySettings.newBuilder()
                    .setCredentialsProvider { credentials }
                    .build()

                photosLibraryClient = PhotosLibraryClient.initialize(settings)

                photosLibraryClient?.let { client ->
                    for (albumId in selectedAlbums) {
                        try {
                            val request = SearchMediaItemsRequest.newBuilder()
                                .setAlbumId(albumId)
                                .setPageSize(100)
                                .build()

                            client.searchMediaItems(request).iterateAll().forEach { mediaItem ->
                                if (mediaItem.mediaMetadata.hasPhoto()) {
                                    mediaItem.baseUrl?.let { url ->
                                        photoUrls.add("$url$PHOTO_QUALITY")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading album $albumId: ${e.message}")
                        }
                    }
                }

                photoUrls.shuffle()

            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos: ${e.message}")
            }
        }
    }

    fun getPhotoUrl(index: Int): String? {
        return if (photoUrls.isNotEmpty()) {
            photoUrls[index % photoUrls.size]
        } else null
    }

    fun getPhotoCount(): Int = photoUrls.size

    suspend fun preloadNextPhoto(index: Int) {
        if (photoUrls.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                val url = photoUrls[index % photoUrls.size]
                Glide.with(context)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading photo: ${e.message}")
            }
        }
    }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val accessToken = prefs.getString("access_token", null)
            val tokenExpiration = prefs.getLong("token_expiration", 0)

            if (accessToken == null || System.currentTimeMillis() > tokenExpiration - 60000) {
                refreshTokens()
            }

            val currentToken = prefs.getString("access_token", null)
                ?: throw Exception("No access token available")

            val expirationTime = prefs.getLong("token_expiration", 0)
            val googleAccessToken = AccessToken(currentToken, Date(expirationTime))

            return@withContext GoogleCredentials.create(googleAccessToken)
                .createScoped(listOf("https://www.googleapis.com/auth/photoslibrary.readonly"))

        } catch (e: Exception) {
            Log.e(TAG, "Error getting/refreshing credentials: ${e.message}")
            null
        }
    }

    private suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val refreshToken = prefs.getString("refresh_token", null)
                ?: throw Exception("No refresh token available")
            val clientId = context.getString(com.example.screensaver.R.string.google_oauth_client_id)
            val clientSecret = context.getString(com.example.screensaver.R.string.google_oauth_client_secret)

            val connection = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val postData = StringBuilder()
                .append("grant_type=refresh_token")
                .append("&refresh_token=").append(refreshToken)
                .append("&client_id=").append(clientId)
                .append("&client_secret=").append(clientSecret)
                .toString()

            connection.outputStream.use { it.write(postData.toByteArray()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                prefs.edit()
                    .putString("access_token", jsonResponse.getString("access_token"))
                    .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                    .apply()

                true
            } else {
                Log.e(TAG, "Failed to refresh token: ${connection.responseMessage}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            false
        }
    }

    fun cleanup() {
        coroutineScope.cancel()
        photosLibraryClient?.shutdown()
        photosLibraryClient = null
        instance = null
    }
}