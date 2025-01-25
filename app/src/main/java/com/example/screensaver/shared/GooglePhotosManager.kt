package com.example.screensaver.shared

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import com.example.screensaver.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive

@Singleton
class GooglePhotosManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val mediaItems = mutableListOf<MediaItem>()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val photosLoadJob = MutableStateFlow<Job?>(null)

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    companion object {
        private const val TAG = "GooglePhotosManager"
        private const val MAX_RETRIES = 3
        private const val PHOTO_QUALITY = "=w2048-h1024"
        private const val PAGE_SIZE = 100
        private const val TOKEN_EXPIRY_BUFFER = 60000L // 1 minute buffer
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        cleanup() // Ensure any existing client is properly cleaned up

        if (!hasValidTokens()) {
            Log.d(TAG, "No tokens available, skipping initialization")
            return@withContext false
        }

        try {
            _loadingState.value = LoadingState.LOADING

            val credentials = getOrRefreshCredentials() ?: return@withContext false
            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            photosLibraryClient = PhotosLibraryClient.initialize(settings)
            _loadingState.value = LoadingState.SUCCESS
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Google Photos", e)
            _loadingState.value = LoadingState.ERROR
            cleanup() // Clean up if initialization fails
            false
        }
    }

    suspend fun getAlbums(): List<Album> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Getting albums...")
            if (!initialize()) {
                Log.e(TAG, "Failed to initialize before getting albums")
                return@withContext emptyList()
            }

            val albums = photosLibraryClient?.listAlbums()?.iterateAll()?.map { googleAlbum ->
                Log.d(TAG, "Found album: ${googleAlbum.title} with ${googleAlbum.mediaItemsCount} items")

                val coverPhotoUrl = if (!googleAlbum.coverPhotoMediaItemId.isNullOrEmpty()) {
                    photosLibraryClient?.getMediaItem(googleAlbum.coverPhotoMediaItemId)?.baseUrl + PHOTO_QUALITY
                } else {
                    ""
                }

                Album(
                    id = googleAlbum.id,
                    title = googleAlbum.title,
                    coverPhotoUrl = coverPhotoUrl,
                    mediaItemsCount = googleAlbum.mediaItemsCount.toInt()
                )
            }?.toList() ?: emptyList()

            Log.d(TAG, "Retrieved ${albums.size} albums")
            albums
        } catch (e: Exception) {
            Log.e(TAG, "Error getting albums", e)
            emptyList()
        }
    }

    fun getPhotoCount(): Int = mediaItems.size

    fun getPhotoUrl(index: Int): String? {
        return if (index in 0 until mediaItems.size) {
            mediaItems[index].baseUrl
        } else {
            null
        }
    }

    suspend fun loadPhotos(): List<MediaItem>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting to load photos...")
            // Cancel any existing load job
            photosLoadJob.value?.cancelAndJoin()

            if (!initialize()) {
                Log.e(TAG, "Failed to initialize Google Photos client")
                return@withContext null
            }

            val selectedAlbumIds = PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet("selected_albums", emptySet()) ?: emptySet()

            Log.d(TAG, "Selected album IDs: $selectedAlbumIds")

            if (selectedAlbumIds.isEmpty()) {
                Log.d(TAG, "No albums selected")
                return@withContext null
            }

            val allPhotos = mutableListOf<MediaItem>()

            // Create new load job
            photosLoadJob.value = managerScope.launch {
                supervisorScope {
                    selectedAlbumIds.forEach { albumId ->
                        launch {
                            try {
                                Log.d(TAG, "Loading photos for album: $albumId")
                                val albumPhotos = loadAlbumPhotos(albumId)
                                if (albumPhotos != null) {
                                    Log.d(TAG, "Loaded ${albumPhotos.size} photos from album $albumId")
                                    synchronized(allPhotos) {
                                        allPhotos.addAll(albumPhotos)
                                    }
                                }
                            } catch (e: Exception) {
                                if (e !is CancellationException) {
                                    Log.e(TAG, "Error loading photos for album $albumId", e)
                                }
                            }
                        }
                    }
                }
            }

            // Wait for load job to complete
            photosLoadJob.value?.join()
            Log.d(TAG, "Finished loading photos. Total photos loaded: ${allPhotos.size}")

            synchronized(mediaItems) {
                mediaItems.clear()
                mediaItems.addAll(allPhotos)
            }

            allPhotos
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "Error fetching photos", e)
            }
            null
        }
    }

    private suspend fun loadAlbumPhotos(albumId: String): List<MediaItem>? =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting to load photos for album: $albumId")
                ensureActive()

                if (!initialize()) {
                    Log.e(TAG, "Failed to initialize Google Photos client")
                    return@withContext null
                }

                val items = mutableListOf<MediaItem>()
                var retryCount = 0

                while (retryCount < MAX_RETRIES && isActive) {
                    try {
                        photosLibraryClient?.let { client ->
                            Log.d(TAG, "Attempting to fetch photos, attempt ${retryCount + 1}")
                            fetchPhotosForAlbum(client, albumId, items)
                        } ?: run {
                            Log.e(TAG, "PhotosLibraryClient is null")
                            return@withContext null
                        }

                        if (items.isNotEmpty()) {
                            Log.d(TAG, "Successfully loaded ${items.size} photos from album $albumId")
                            return@withContext items
                        }
                        Log.d(TAG, "No photos found in album $albumId")
                        break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e

                        if (shouldRetryOnError(e) && retryCount < MAX_RETRIES - 1) {
                            retryCount++
                            Log.d(TAG, "Retrying photo load, attempt $retryCount")
                            refreshTokens()
                            delay(1000)
                            continue
                        }
                        throw e
                    }
                }
                null
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error loading photos for album $albumId", e)
                }
                null
            }
        }

    private suspend fun fetchPhotosForAlbum(
        client: PhotosLibraryClient,
        albumId: String,
        items: MutableList<MediaItem>
    ) {
        Log.d(TAG, "Fetching photos for album: $albumId")

        val request = SearchMediaItemsRequest.newBuilder()
            .setAlbumId(albumId)
            .setPageSize(PAGE_SIZE)
            .build()

        try {
            val mediaItems = client.searchMediaItems(request).iterateAll()
            Log.d(TAG, "Retrieved ${mediaItems.count()} items from Google Photos API")

            mediaItems.forEach { googleMediaItem ->
                Log.d(TAG, "Processing item: ${googleMediaItem.id}, hasPhoto: ${googleMediaItem.mediaMetadata.hasPhoto()}")
                if (googleMediaItem.mediaMetadata.hasPhoto()) {
                    googleMediaItem.baseUrl?.let { baseUrl ->
                        Log.d(TAG, "Adding photo with baseUrl: $baseUrl")
                        items.add(MediaItem(
                            id = googleMediaItem.id,
                            albumId = albumId,
                            baseUrl = "$baseUrl$PHOTO_QUALITY",
                            mimeType = googleMediaItem.mimeType,
                            width = googleMediaItem.mediaMetadata.width.toInt(),
                            height = googleMediaItem.mediaMetadata.height.toInt()
                        ))
                    }
                }
            }

            Log.d(TAG, "Added ${items.size} photos from album $albumId")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos for album $albumId", e)
            throw e
        }
    }

    private fun shouldRetryOnError(error: Exception): Boolean {
        Log.d(TAG, "Checking if should retry for error: ${error.message}")
        val shouldRetry = error.message?.contains("UNAUTHENTICATED") == true
        Log.d(TAG, "Should retry: $shouldRetry")
        return shouldRetry
    }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val accessToken = prefs.getString("access_token", null)
            val refreshToken = prefs.getString("refresh_token", null)
            val tokenExpiration = prefs.getLong("token_expiration", 0)

            Log.d(TAG, "Checking credentials - Access Token: ${accessToken != null}, Refresh Token: ${refreshToken != null}")

            if (accessToken == null || refreshToken == null) {
                Log.d(TAG, "Missing tokens")
                return@withContext null
            }

            if (System.currentTimeMillis() > tokenExpiration - TOKEN_EXPIRY_BUFFER) {
                Log.d(TAG, "Token expired, refreshing")
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

    fun hasValidTokens(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val accessToken = prefs.getString("access_token", null)
        val tokenExpiration = prefs.getLong("token_expiration", 0)
        return accessToken != null && tokenExpiration > System.currentTimeMillis()
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

    private fun buildPostData(refreshToken: String, clientId: String, clientSecret: String): String =
        buildString {
            append("grant_type=refresh_token")
            append("&refresh_token=").append(refreshToken)
            append("&client_id=").append(clientId)
            append("&client_secret=").append(clientSecret)
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
        try {
            runBlocking {
                photosLoadJob.value?.cancelAndJoin()
            }

            photosLibraryClient?.let { client ->
                try {
                    client.close()
                    Thread.sleep(100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during client shutdown", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            photosLibraryClient = null
            synchronized(mediaItems) {
                mediaItems.clear()
            }
            managerScope.cancel()
        }
    }
}