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
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.data.SecureStorage
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors
import com.google.api.gax.core.InstantiatingExecutorProvider
import java.util.concurrent.RejectedExecutionException

@Singleton
class GooglePhotosManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val secureStorage: SecureStorage
) {
    private val albumCache = mutableMapOf<String, GoogleAlbum>()
    private val albumCacheExpiry = 5 * 60 * 1000 // 5 minutes
    private var lastAlbumFetch: Long = 0

    private var photosLibraryClient: PhotosLibraryClient? = null
    private val mediaItems = mutableListOf<MediaItem>()

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val photosLoadJob = MutableStateFlow<Job?>(null)

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val loadingState: StateFlow<LoadingState> = _loadingState

    private var executorService = Executors.newScheduledThreadPool(4)
    private val executorMutex = Mutex()

    private val _photoLoadingState = MutableStateFlow<LoadingState>(LoadingState.IDLE)
    val photoLoadingState: StateFlow<LoadingState> = _photoLoadingState

    enum class LoadingState {
        IDLE, LOADING, SUCCESS, ERROR
    }

    companion object {
        private const val TAG = "GooglePhotosManager"
        private const val MAX_RETRIES = 3
        private const val PHOTO_QUALITY = "=w2048-h1024"
        private const val PAGE_SIZE = 100
        private const val TOKEN_EXPIRY_BUFFER = 60000L // 1 minute buffer

        private val REQUIRED_SCOPES = listOf(
            "https://www.googleapis.com/auth/photoslibrary.readonly",
            //"https://www.googleapis.com/auth/photoslibrary",
            //"https://www.googleapis.com/auth/photos.readonly"
        )
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting initialization...")
            cleanup() // Ensure any existing client is properly cleaned up

            if (!hasValidTokens()) {
                Log.e(TAG, "No valid tokens available")
                return@withContext false
            }

            val credentials = getOrRefreshCredentials()
            if (credentials == null) {
                Log.e(TAG, "Failed to get or refresh credentials")
                return@withContext false
            }
            Log.d(TAG, "Successfully obtained credentials")

            try {
                val settings = createPhotosLibrarySettings(credentials)
                Log.d(TAG, "Created PhotosLibrarySettings")

                photosLibraryClient = PhotosLibraryClient.initialize(settings)
                Log.d(TAG, "Successfully initialized PhotosLibraryClient")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during client initialization", e)
                cleanup()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initialize", e)
            cleanup()
            false
        }
    }

    private suspend fun createPhotosLibrarySettings(credentials: GoogleCredentials): PhotosLibrarySettings {
        return withContext(Dispatchers.IO) {
            executorMutex.withLock {
                val currentExecutor = if (executorService.isShutdown || executorService.isTerminated) {
                    Executors.newScheduledThreadPool(8).also { executorService = it }
                } else executorService

                PhotosLibrarySettings.newBuilder()
                    .setCredentialsProvider { credentials }
                    .setExecutorProvider(
                        InstantiatingExecutorProvider.newBuilder()
                            .setExecutorThreadCount(8)
                            .build()
                    )
                    .build()
            }
        }
    }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val credentials = secureStorage.getGoogleCredentials()
            Log.d(TAG, "Checking credentials - Access Token: ${credentials != null}")

            if (credentials == null) {
                Log.d(TAG, "Missing tokens")
                return@withContext null
            }

            if (credentials.needsRefresh) {
                Log.d(TAG, "Token expired or needs refresh")
                if (!refreshTokens()) {
                    return@withContext null
                }
                // Get fresh credentials after refresh
                val updatedCredentials = secureStorage.getGoogleCredentials() ?: return@withContext null
                return@withContext createGoogleCredentials(updatedCredentials)
            }

            createGoogleCredentials(credentials)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting/refreshing credentials", e)
            null
        }
    }

    private fun createGoogleCredentials(credentials: SecureStorage.GoogleCredentials): GoogleCredentials {
        return GoogleCredentials.create(AccessToken(credentials.accessToken, Date(credentials.expirationTime)))
            .createScoped(REQUIRED_SCOPES)
    }

    fun hasValidTokens(): Boolean {
        val credentials = secureStorage.getGoogleCredentials()
        return credentials != null &&
                credentials.accessToken.isNotEmpty() &&
                credentials.expirationTime > System.currentTimeMillis() + TOKEN_EXPIRY_BUFFER
    }

    suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val credentials = secureStorage.getGoogleCredentials()
                ?: throw Exception("No credentials available")

            val refreshToken = credentials.refreshToken
            val clientId = context.getString(R.string.google_oauth_client_id)
            val clientSecret = context.getString(R.string.google_oauth_client_secret)

            connection = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }

            val postData = buildPostData(refreshToken, clientId, clientSecret)
            connection.outputStream.use { it.write(postData.toByteArray()) }

            secureStorage.recordRefreshAttempt()

            return@withContext when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> handleSuccessfulTokenRefresh(connection, credentials)
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
        currentCredentials: SecureStorage.GoogleCredentials
    ): Boolean {
        return try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(response)

            val newAccessToken = jsonResponse.getString("access_token")
            val expirationTime = System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000)

            secureStorage.saveGoogleCredentials(
                accessToken = newAccessToken,
                refreshToken = currentCredentials.refreshToken,
                expirationTime = expirationTime,
                email = currentCredentials.email
            )

            Log.d(TAG, "Successfully refreshed token")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling token refresh response", e)
            false
        }
    }

    private fun handleFailedTokenRefresh(connection: HttpURLConnection): Boolean {
        Log.e(TAG, "Failed to refresh token: ${connection.responseMessage}")
        return false
    }

    private fun shouldUseCache(): Boolean {
        val now = System.currentTimeMillis()
        return albumCache.isNotEmpty() && (now - lastAlbumFetch < albumCacheExpiry)
    }

    fun prewarmCache() {
        managerScope.launch {
            try {
                getAlbums() // Now using managerScope instead of scope
            } catch (e: Exception) {
                Log.e(TAG, "Error prewarming cache", e)
            }
        }
    }

    suspend fun getAlbums(): List<GoogleAlbum> = withContext(Dispatchers.IO) {
        try {
            if (shouldUseCache()) {
                Log.d(TAG, "Using cached albums")
                return@withContext albumCache.values.toList()
            }

            if (!initialize()) {
                Log.e(TAG, "Failed to initialize Google Photos client")
                return@withContext emptyList()
            }

            photosLibraryClient?.let { client ->
                try {
                    val startTime = System.currentTimeMillis()
                    Log.d(TAG, "Starting album fetch")

                    // Use a larger batch size
                    val batchSize = 100
                    val albumItems = client.listAlbums(false)
                        .iterateAll()
                        .toList()

                    // Process albums in batches
                    coroutineScope {
                        val results = albumItems.chunked(batchSize).map { batch ->
                            async {
                                batch.map { album ->
                                    GoogleAlbum(
                                        id = album.id,
                                        title = album.title,
                                        coverPhotoUrl = album.coverPhotoBaseUrl,
                                        mediaItemsCount = album.mediaItemsCount
                                    )
                                }
                            }
                        }.awaitAll().flatten()

                        // Update cache
                        albumCache.clear()
                        albumCache.putAll(results.associateBy { it.id })
                        lastAlbumFetch = System.currentTimeMillis()

                        val duration = System.currentTimeMillis() - startTime
                        Log.d(TAG, "Loaded ${results.size} albums in ${duration}ms")
                        results
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching albums", e)
                    emptyList()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error in getAlbums", e)
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
            _photoLoadingState.value = LoadingState.LOADING

            if (!initialize()) {
                Log.e(TAG, "Failed to initialize Google Photos client")
                _photoLoadingState.value = LoadingState.ERROR
                return@withContext null
            }

            // Use the injected preferences instead of PreferenceManager
            val selectedAlbumIds = preferences.getSelectedAlbumIds()

            Log.d(TAG, "Selected album IDs: $selectedAlbumIds")

            if (selectedAlbumIds.isEmpty()) {
                Log.d(TAG, "No albums selected")
                _photoLoadingState.value = LoadingState.ERROR
                return@withContext null
            }

            val allPhotos = mutableListOf<MediaItem>()
            var totalProcessed = 0

            selectedAlbumIds.forEach { albumId ->
                try {
                    Log.d(TAG, "Starting to load photos for album: $albumId")

                    val request = SearchMediaItemsRequest.newBuilder()
                        .setAlbumId(albumId)
                        .setPageSize(PAGE_SIZE)
                        .build()

                    photosLibraryClient?.searchMediaItems(request)?.let { response ->
                        var photoCount = 0
                        response.iterateAll().forEach { googleMediaItem ->
                            if (googleMediaItem.mediaMetadata.hasPhoto()) {
                                googleMediaItem.baseUrl?.let { baseUrl ->
                                    allPhotos.add(MediaItem(
                                        id = googleMediaItem.id,
                                        albumId = albumId,
                                        baseUrl = "$baseUrl$PHOTO_QUALITY",
                                        mimeType = googleMediaItem.mimeType,
                                        width = googleMediaItem.mediaMetadata.width.toInt(),
                                        height = googleMediaItem.mediaMetadata.height.toInt()
                                    ))
                                    photoCount++
                                    totalProcessed++
                                    if (totalProcessed % 10 == 0) {
                                        Log.d(TAG, "Progress: Processed $totalProcessed photos")
                                    }
                                }
                            }
                        }
                        Log.d(TAG, "Loaded $photoCount photos from album $albumId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading photos for album $albumId", e)
                }
            }

            Log.d(TAG, "Total photos loaded: ${allPhotos.size}")

            if (allPhotos.isNotEmpty()) {
                synchronized(mediaItems) {
                    mediaItems.clear()
                    mediaItems.addAll(allPhotos)
                }
                _photoLoadingState.value = LoadingState.SUCCESS
                allPhotos
            } else {
                Log.w(TAG, "No photos were loaded")
                _photoLoadingState.value = LoadingState.ERROR
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos", e)
            _photoLoadingState.value = LoadingState.ERROR
            null
        }
    }

    fun hasSavedAlbums(): Boolean {
        return preferences.getSelectedAlbumIds().isNotEmpty()
    }

    private fun shouldRetryOnError(error: Exception): Boolean {
        Log.d(TAG, "Checking if should retry for error: ${error.message}")
        return error.message?.contains("UNAUTHENTICATED") == true
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
                        if (e is RejectedExecutionException) {
                            Log.w(TAG, "Executor rejected task, attempting recovery")
                            recoverFromExecutorFailure()
                            retryCount++
                            continue
                        }
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
            mediaItems.forEach { googleMediaItem ->
                if (googleMediaItem.mediaMetadata.hasPhoto()) {
                    googleMediaItem.baseUrl?.let { baseUrl ->
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
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching photos for album $albumId", e)
            throw e
        }
    }

    private suspend fun recoverFromExecutorFailure() {
        executorMutex.withLock {
            try {
                executorService = if (executorService.isShutdown || executorService.isTerminated) {
                    Executors.newScheduledThreadPool(8)
                } else {
                    executorService
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering from executor failure", e)
                executorService = Executors.newScheduledThreadPool(8)
            }
        }
    }

    private suspend fun safeShutdownExecutor() {
        executorMutex.withLock {
            try {
                if (!executorService.isShutdown) {
                    val currentExecutor = executorService
                    executorService = Executors.newScheduledThreadPool(4)
                    currentExecutor.shutdown()
                    if (!currentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        currentExecutor.shutdownNow()
                    } else {
                        Log.d(TAG, "Executor terminated normally")
                    }
                } else {
                    Log.d(TAG, "Executor already shutdown")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during executor shutdown", e)
            }
        }
    }

    fun cleanup() {
        try {
            photosLibraryClient?.let { client ->
                try {
                    client.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing client", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        } finally {
            runBlocking {
                safeShutdownExecutor()
            }
            photosLibraryClient = null
            synchronized(mediaItems) {
                mediaItems.clear()
            }
        }
    }
}