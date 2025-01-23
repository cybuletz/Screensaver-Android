// File: app/src/main/java/com/example/screensaver/shared/GooglePhotosManager.kt

package com.example.screensaver.shared

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class GooglePhotosManager private constructor(private val context: Context) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val photoUrls = mutableListOf<String>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "GooglePhotosManager"
        private const val PHOTO_QUALITY = "=w2560-h1440" // 2K quality
        private const val MAX_RETRIES = 3

        @Volatile
        private var instance: GooglePhotosManager? = null

        fun getInstance(context: Context): GooglePhotosManager {
            return instance ?: synchronized(this) {
                instance ?: GooglePhotosManager(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val credentials = getOrRefreshCredentials() ?: return@withContext false
            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            photosLibraryClient = PhotosLibraryClient.initialize(settings)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Google Photos: ${e.message}")
            false
        }
    }

    suspend fun loadPhotos(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!initialize()) {
                return@withContext false
            }

            val prefs = context.getSharedPreferences("screensaver_prefs", Context.MODE_PRIVATE)
            val selectedAlbums = prefs.getStringSet("selected_albums", emptySet()) ?: emptySet()

            if (selectedAlbums.isEmpty()) {
                Log.d(TAG, "No albums selected")
                return@withContext false
            }

            val newUrls = mutableListOf<String>()
            var retryCount = 0

            while (retryCount < MAX_RETRIES) {
                try {
                    photosLibraryClient?.let { client ->
                        for (albumId in selectedAlbums) {
                            val request = SearchMediaItemsRequest.newBuilder()
                                .setAlbumId(albumId)
                                .setPageSize(100)
                                .build()

                            client.searchMediaItems(request).iterateAll().forEach { mediaItem ->
                                if (mediaItem.mediaMetadata.hasPhoto()) {
                                    mediaItem.baseUrl?.let { url ->
                                        newUrls.add("$url$PHOTO_QUALITY")
                                    }
                                }
                            }
                        }
                    }

                    if (newUrls.isNotEmpty()) {
                        synchronized(photoUrls) {
                            photoUrls.clear()
                            photoUrls.addAll(newUrls)
                            photoUrls.shuffle()
                        }
                        return@withContext true
                    }
                    break
                } catch (e: Exception) {
                    if (e.message?.contains("UNAUTHENTICATED") == true && retryCount < MAX_RETRIES - 1) {
                        retryCount++
                        refreshTokens()
                        delay(1000)
                        continue
                    }
                    throw e
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error loading photos: ${e.message}")
            false
        }
    }

    fun getPhotoUrl(index: Int): String? {
        return synchronized(photoUrls) {
            if (photoUrls.isNotEmpty()) {
                photoUrls[index % photoUrls.size]
            } else null
        }
    }

    fun getPhotoCount(): Int = synchronized(photoUrls) { photoUrls.size }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val accessToken = prefs.getString("access_token", null)
            val tokenExpiration = prefs.getLong("token_expiration", 0)

            if (accessToken == null || System.currentTimeMillis() > tokenExpiration - 60000) {
                if (!refreshTokens()) {
                    return@withContext null
                }
            }

            val currentToken = prefs.getString("access_token", null)
                ?: throw Exception("No access token available")

            val expirationTime = prefs.getLong("token_expiration", 0)
            GoogleCredentials.create(AccessToken(currentToken, Date(expirationTime)))
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

            val clientId = context.getString(R.string.google_oauth_client_id)
            val clientSecret = context.getString(R.string.google_oauth_client_secret)

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
    }
}