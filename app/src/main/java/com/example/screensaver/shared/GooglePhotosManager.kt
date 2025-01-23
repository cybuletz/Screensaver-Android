package com.example.screensaver.shared

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GooglePhotosManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val photoUrls = mutableListOf<String>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // Changed to SupervisorJob for better error handling

    companion object {
        private const val TAG = "GooglePhotosManager"
        private const val MAX_RETRIES = 3
        private const val PHOTO_QUALITY = "=w2048-h1024" // Adjust quality parameter as needed
        private const val PAGE_SIZE = 100
        private const val TOKEN_EXPIRY_BUFFER = 60000L // 1 minute buffer for token expiration

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
            if (photosLibraryClient != null) {
                return@withContext true
            }

            val credentials = getOrRefreshCredentials() ?: return@withContext false
            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            photosLibraryClient = PhotosLibraryClient.initialize(settings)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Google Photos", e)
            false
        }
    }

    suspend fun loadPhotos(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!initialize()) {
                Log.e(TAG, "Failed to initialize Google Photos client")
                return@withContext false
            }

            val selectedAlbums = getSelectedAlbums()
            if (selectedAlbums.isEmpty()) {
                Log.d(TAG, "No albums selected")
                return@withContext false
            }

            val newUrls = mutableListOf<String>()
            var retryCount = 0

            while (retryCount < MAX_RETRIES) {
                try {
                    photosLibraryClient?.let { client ->
                        selectedAlbums.forEach { albumId ->
                            fetchPhotosForAlbum(client, albumId, newUrls)
                        }
                    }

                    if (newUrls.isNotEmpty()) {
                        updatePhotoUrls(newUrls)
                        return@withContext true
                    }
                    break
                } catch (e: Exception) {
                    if (shouldRetryOnError(e) && retryCount < MAX_RETRIES - 1) {
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
            Log.e(TAG, "Error loading photos", e)
            false
        }
    }

    private fun getSelectedAlbums(): Set<String> {
        return context.getSharedPreferences("screensaver_prefs", Context.MODE_PRIVATE)
            .getStringSet("selected_albums", emptySet()) ?: emptySet()
    }

    private suspend fun fetchPhotosForAlbum(
        client: PhotosLibraryClient,
        albumId: String,
        urls: MutableList<String>
    ) {
        val request = SearchMediaItemsRequest.newBuilder()
            .setAlbumId(albumId)
            .setPageSize(PAGE_SIZE)
            .build()

        client.searchMediaItems(request).iterateAll().forEach { mediaItem ->
            if (mediaItem.mediaMetadata.hasPhoto()) {
                mediaItem.baseUrl?.let { url ->
                    urls.add("$url$PHOTO_QUALITY")
                }
            }
        }
    }

    private fun updatePhotoUrls(newUrls: List<String>) {
        synchronized(photoUrls) {
            photoUrls.clear()
            photoUrls.addAll(newUrls)
            photoUrls.shuffle()
        }
    }

    private fun shouldRetryOnError(error: Exception): Boolean {
        return error.message?.contains("UNAUTHENTICATED") == true
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

            if (accessToken == null || System.currentTimeMillis() > tokenExpiration - TOKEN_EXPIRY_BUFFER) {
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
            Log.e(TAG, "Error getting/refreshing credentials", e)
            null
        }
    }

    private suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val refreshToken = prefs.getString("refresh_token", null)
                ?: throw Exception("No refresh token available")

            val clientId = context.getString(R.string.google_oauth_client_id)
            val clientSecret = context.getString(R.string.google_oauth_client_secret)

            connection = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val postData = buildPostData(refreshToken, clientId, clientSecret)
            connection.outputStream.use { it.write(postData.toByteArray()) }

            return@withContext when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> handleSuccessfulTokenRefresh(connection, prefs)
                else -> handleFailedTokenRefresh(connection)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildPostData(refreshToken: String, clientId: String, clientSecret: String): String {
        return buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(refreshToken)
            append("&client_id=").append(clientId)
            append("&client_secret=").append(clientSecret)
        }
    }

    private fun handleSuccessfulTokenRefresh(
        connection: HttpURLConnection,
        prefs: android.content.SharedPreferences
    ): Boolean {
        val response = connection.inputStream.bufferedReader().use { it.readText() }
        val jsonResponse = JSONObject(response)

        prefs.edit()
            .putString("access_token", jsonResponse.getString("access_token"))
            .putLong(
                "token_expiration",
                System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000)
            )
            .apply()

        return true
    }

    private fun handleFailedTokenRefresh(connection: HttpURLConnection): Boolean {
        Log.e(TAG, "Failed to refresh token: ${connection.responseMessage}")
        return false
    }

    fun cleanup() {
        coroutineScope.cancel()
        photosLibraryClient?.shutdown()
        photosLibrinaryClient = null
        synchronized(photoUrls) {
            photoUrls.clear()
        }
    }
}