package com.example.screensaver

import android.service.dreams.DreamService
import android.widget.ImageView
import kotlinx.coroutines.*
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.photos.types.proto.MediaItem
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import android.widget.Toast
import android.graphics.Color
import android.view.View
import com.bumptech.glide.request.RequestOptions
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions

class PhotoDreamService : DreamService() {
    private lateinit var primaryImageView: ImageView
    private lateinit var secondaryImageView: ImageView
    private var currentImageView: ImageView? = null
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var currentPhotoIndex = 0
    private var photoUrls = mutableListOf<String>()
    private var slideshowRunnable: Runnable? = null
    private var retryCount = 0

    companion object {
        private const val TAG = "PhotoDreamService"
        private const val SLIDESHOW_DELAY = 10000L // 10 seconds
        private const val PHOTO_QUALITY = "=w2560-h1440" // 2K quality
        private const val MAX_RETRIES = 3
        private const val TRANSITION_DURATION = 1000L // 1 second transition
    }

    private fun logDebugInfo(message: String) {
        Log.e(TAG, message)  // Using Log.e for better visibility
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        logDebugInfo("onAttachedToWindow - Starting service setup")
        try {
            setupDreamService()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAttachedToWindow", e)
            handleError(e)
        }
    }

    private fun setupDreamService() {
        logDebugInfo("Setting up dream service")
        isFullscreen = true
        isInteractive = true  // Set to true for debugging
        setScreenBright(true)

        try {
            setContentView(R.layout.dream_layout)
            logDebugInfo("Content view set")

            primaryImageView = findViewById(R.id.primaryImageView)
            secondaryImageView = findViewById(R.id.secondaryImageView)
            currentImageView = primaryImageView

            logDebugInfo("ImageViews initialized")

            // Initialize both ImageViews
            primaryImageView.scaleType = ImageView.ScaleType.CENTER_CROP
            secondaryImageView.scaleType = ImageView.ScaleType.CENTER_CROP
            secondaryImageView.alpha = 0f

            // Set background colors for visibility
            primaryImageView.setBackgroundColor(Color.BLUE)
            secondaryImageView.setBackgroundColor(Color.RED)

            logDebugInfo("Starting verification")
            verifyAndInitialize()
        } catch (e: Exception) {
            logDebugInfo("Setup error: ${e.message}")
            throw e
        }
    }

    private fun verifyAndInitialize() {
        coroutineScope.launch {
            try {
                verifySelectedAlbums()
                initializePhotosLibraryClient()
            } catch (e: Exception) {
                Log.e(TAG, "Error in verification and initialization", e)
                handleError(e)
            }
        }
    }

    private fun verifySelectedAlbums() {
        val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
        val selectedAlbums = prefs.getStringSet("selected_albums", emptySet())
        logDebugInfo("Selected albums: ${selectedAlbums?.joinToString()}")

        if (selectedAlbums.isNullOrEmpty()) {
            logDebugInfo("No albums selected!")
            throw IllegalStateException("No albums selected")
        }
    }

    private suspend fun getOrRefreshCredentials(serverAuthCode: String?): OAuth2Credentials {
        return withContext(Dispatchers.IO) {
            logDebugInfo("Getting/refreshing credentials")
            val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
            val accessTokenString = prefs.getString("access_token", null)
            val expirationTime = prefs.getLong("token_expiration", 0)

            if (accessTokenString == null || System.currentTimeMillis() >= expirationTime) {
                if (serverAuthCode == null) {
                    logDebugInfo("No server auth code available")
                    throw IllegalStateException("No server auth code available for token refresh")
                }
                refreshAccessToken(serverAuthCode)
            }

            val updatedAccessToken = prefs.getString("access_token", "")
                ?: throw IllegalStateException("Access token not found")
            val updatedExpirationTime = prefs.getLong("token_expiration", 0)

            logDebugInfo("Credentials obtained successfully")
            OAuth2Credentials.create(AccessToken.newBuilder()
                .setTokenValue(updatedAccessToken)
                .setExpirationTime(Date(updatedExpirationTime))
                .build())
        }
    }

    private suspend fun initializePhotosLibraryClient() {
        withContext(Dispatchers.IO) {
            try {
                logDebugInfo("Initializing Photos Library Client")
                val account = GoogleSignIn.getLastSignedInAccount(this@PhotoDreamService)
                    ?: throw IllegalStateException("No Google account found")

                val credentials = getOrRefreshCredentials(account.serverAuthCode)
                val settings = PhotosLibrarySettings.newBuilder()
                    .setCredentialsProvider { credentials }
                    .build()

                photosLibraryClient = PhotosLibraryClient.initialize(settings)
                logDebugInfo("PhotosLibraryClient initialized successfully")
                loadSelectedAlbumPhotos()
            } catch (e: Exception) {
                logDebugInfo("Failed to initialize client: ${e.message}")
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    logDebugInfo("Retrying initialization (attempt $retryCount)")
                    delay(2000)
                    initializePhotosLibraryClient()
                } else {
                    throw e
                }
            }
        }
    }

    private suspend fun loadSelectedAlbumPhotos() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
                val selectedAlbums = prefs.getStringSet("selected_albums", emptySet()) ?: emptySet()
                logDebugInfo("Loading photos from ${selectedAlbums.size} albums")

                photoUrls.clear()
                var totalPhotos = 0

                photosLibraryClient?.let { client ->
                    for (albumId in selectedAlbums) {
                        try {
                            logDebugInfo("Loading album: $albumId")
                            val request = SearchMediaItemsRequest.newBuilder()
                                .setAlbumId(albumId)
                                .setPageSize(100)
                                .build()

                            client.searchMediaItems(request).iterateAll().forEach { mediaItem ->
                                if (mediaItem.mediaMetadata.hasPhoto()) {
                                    mediaItem.baseUrl?.let { url ->
                                        photoUrls.add("$url$PHOTO_QUALITY")
                                        totalPhotos++
                                    }
                                }
                            }
                            logDebugInfo("Found $totalPhotos photos in album $albumId")
                        } catch (e: Exception) {
                            logDebugInfo("Error loading album $albumId: ${e.message}")
                        }
                    }
                }

                if (photoUrls.isNotEmpty()) {
                    photoUrls.shuffle()
                    logDebugInfo("Starting slideshow with ${photoUrls.size} photos")
                    withContext(Dispatchers.Main) {
                        startSlideshow()
                    }
                } else {
                    logDebugInfo("No photos found in any albums")
                    throw IllegalStateException("No photos found in selected albums")
                }
            } catch (e: Exception) {
                logDebugInfo("Error in loadSelectedAlbumPhotos: ${e.message}")
                throw e
            }
        }
    }

    private suspend fun refreshAccessToken(serverAuthCode: String) {
        withContext(Dispatchers.IO) {
            try {
                logDebugInfo("Refreshing access token")
                val tokenEndpoint = "https://oauth2.googleapis.com/token"
                val clientId = getString(R.string.google_oauth_client_id)
                val clientSecret = getString(R.string.google_oauth_client_secret)

                val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "code=$serverAuthCode" +
                        "&client_id=$clientId" +
                        "&client_secret=$clientSecret" +
                        "&grant_type=authorization_code" +
                        "&redirect_uri=urn:ietf:wg:oauth:2.0:oob"

                connection.outputStream.use { it.write(postData.toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val accessTokenString = jsonResponse.getString("access_token")
                    val expiresIn = jsonResponse.getLong("expires_in")
                    val expirationTime = System.currentTimeMillis() + (expiresIn * 1000)

                    getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("access_token", accessTokenString)
                        .putLong("token_expiration", expirationTime)
                        .apply()

                    logDebugInfo("Successfully refreshed access token")
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    logDebugInfo("Failed to refresh token: $error")
                    throw Exception("Token refresh failed: ${connection.responseMessage}")
                }
            } catch (e: Exception) {
                logDebugInfo("Failed to refresh access token: ${e.message}")
                throw e
            }
        }
    }

    private fun startSlideshow() {
        if (photoUrls.isEmpty()) {
            logDebugInfo("No photos available for slideshow")
            return
        }

        logDebugInfo("Starting slideshow with ${photoUrls.size} photos")
        // Pre-cache next few images
        precacheImages(5)

        slideshowRunnable = object : Runnable {
            override fun run() {
                displayNextPhoto()
                handler.postDelayed(this, SLIDESHOW_DELAY)
            }
        }
        handler.post(slideshowRunnable!!)
    }

    private fun precacheImages(count: Int) {
        logDebugInfo("Pre-caching $count images")
        for (i in 0 until minOf(count, photoUrls.size)) {
            val index = (currentPhotoIndex + i) % photoUrls.size
            Glide.with(this)
                .load(photoUrls[index])
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload()
        }
    }

    private fun displayNextPhoto() {
        if (photoUrls.isEmpty()) {
            logDebugInfo("No photos to display")
            return
        }

        val nextImageView = if (currentImageView == primaryImageView) secondaryImageView else primaryImageView
        val url = photoUrls[currentPhotoIndex]
        logDebugInfo("Loading photo $currentPhotoIndex: ${url.take(50)}...")

        Glide.with(this)
            .load(url)
            .apply(RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(android.R.drawable.ic_dialog_alert))
            .into(nextImageView)

        // Crossfade transition
        ObjectAnimator.ofFloat(nextImageView, View.ALPHA, 0f, 1f).apply {
            duration = TRANSITION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        ObjectAnimator.ofFloat(currentImageView!!, View.ALPHA, 1f, 0f).apply {
            duration = TRANSITION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        currentImageView = nextImageView
        currentPhotoIndex = (currentPhotoIndex + 1) % photoUrls.size

        // Pre-cache next image
        if (currentPhotoIndex < photoUrls.size - 1) {
            Glide.with(this)
                .load(photoUrls[(currentPhotoIndex + 1) % photoUrls.size])
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload()
        }
    }

    private fun handleError(error: Exception) {
        logDebugInfo("Error: ${error.message}")
        coroutineScope.launch(Dispatchers.Main) {
            Toast.makeText(
                this@PhotoDreamService,
                "Error: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        logDebugInfo("onDreamingStarted")
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        logDebugInfo("onDreamingStopped")
        cleanup()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        logDebugInfo("onDetachedFromWindow")
        cleanup()
    }

    private fun cleanup() {
        logDebugInfo("Cleaning up resources")
        slideshowRunnable?.let {
            handler.removeCallbacks(it)
        }
        coroutineScope.cancel()
        photosLibraryClient?.shutdown()
    }
}