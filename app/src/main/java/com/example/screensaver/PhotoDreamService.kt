package com.example.screensaver

import android.animation.ObjectAnimator
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.library.v1.proto.SearchMediaItemsRequest
import com.google.photos.types.proto.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class PhotoDreamService : DreamService() {
    private lateinit var primaryImageView: ImageView
    private lateinit var secondaryImageView: ImageView
    private lateinit var debugStatus: TextView
    private lateinit var debugPhotoInfo: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var rootLayout: FrameLayout
    private var currentImageView: ImageView? = null
    private var photosLibraryClient: PhotosLibraryClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var currentPhotoIndex = 0
    private var photoUrls = mutableListOf<String>()
    private var slideshowRunnable: Runnable? = null
    private var retryCount = 0
    private val dreamStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            Log.e(TAG, "Dream state changed: ${intent?.action}")
            dumpState()
        }
    }

    init {
        Log.wtf(TAG, "PhotoDreamService instance created") // This will show up even with strict filtering
    }

    companion object {
        private const val TAG = "PhotoDreamService"
        private const val SLIDESHOW_DELAY = 10000L // 10 seconds
        private const val PHOTO_QUALITY = "=w2560-h1440" // 2K quality
        private const val MAX_RETRIES = 3
        private const val TRANSITION_DURATION = 1000L // 1 second transition
        private const val RECEIVER_FLAGS = ContextCompat.RECEIVER_NOT_EXPORTED
    }


    init {
        Log.e(TAG, "PhotoDreamService instance created")
    }

    override fun onCreate() {
        super.onCreate()
        Log.wtf(TAG, "PhotoDreamService onCreate called") // Using wtf to ensure visibility

        // Register for dream state changes
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(DreamService.SERVICE_INTERFACE)
        }
        ContextCompat.registerReceiver(
            this,
            dreamStateReceiver,
            filter,
            RECEIVER_FLAGS
        )
    }
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(dreamStateReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        Log.e(TAG, "PhotoDreamService onDestroy called")
    }

    override fun onWakeUp() {
        super.onWakeUp()
        Log.e(TAG, "Dream wake up called")
    }

    private fun logDebugInfo(message: String) {
        Log.e(TAG, message)
        coroutineScope.launch(Dispatchers.Main) {
            try {
                debugStatus?.let { status ->
                    status.text = message
                    status.visibility = View.VISIBLE
                }
                Toast.makeText(this@PhotoDreamService, message, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing debug info", e)
            }
        }
    }

    private fun dumpState() {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
                val selectedAlbums = prefs.getStringSet("selected_albums", emptySet())
                val accessToken = prefs.getString("access_token", null)
                val account = GoogleSignIn.getLastSignedInAccount(this@PhotoDreamService)

                val stateInfo = """
                State Dump:
                Selected Albums: ${selectedAlbums?.size ?: 0}
                Access Token exists: ${accessToken != null}
                Google Account exists: ${account != null}
                Photo URLs loaded: ${photoUrls.size}
                Current Photo Index: $currentPhotoIndex
                PhotosLibraryClient initialized: ${photosLibraryClient != null}
                Loading Indicator visible: ${loadingIndicator.visibility == View.VISIBLE}
                Debug Status visible: ${debugStatus.visibility == View.VISIBLE}
                Primary ImageView visible: ${primaryImageView.visibility == View.VISIBLE}
                Is Fullscreen: ${isFullscreen}
                Is Interactive: ${isInteractive}
            """.trimIndent()

                Log.e(TAG, stateInfo)
                debugPhotoInfo?.text = stateInfo
            } catch (e: Exception) {
                Log.e(TAG, "Error dumping state", e)
            }
        }
    }

    override fun onAttachedToWindow() {
        Log.e(TAG, "PhotoDreamService onAttachedToWindow START")
        super.onAttachedToWindow()
        try {
            isFullscreen = true
            isInteractive = true
            setScreenBright(true)
            Log.e(TAG, "Dream service basic settings configured")
            setupDreamService()
            Log.e(TAG, "PhotoDreamService setup completed")
            dumpState()
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onAttachedToWindow", e)
            handleError(e)
        }
        Log.e(TAG, "PhotoDreamService onAttachedToWindow END")
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
            updateDebugStatus("No photos to display")
            return
        }

        val nextImageView = if (currentImageView == primaryImageView) secondaryImageView else primaryImageView
        val url = photoUrls[currentPhotoIndex]

        logDebugInfo("""
        Starting photo transition:
        Current index: $currentPhotoIndex
        Total photos: ${photoUrls.size}
        Current view: ${if (currentImageView == primaryImageView) "primary" else "secondary"}
        Next view: ${if (nextImageView == primaryImageView) "primary" else "secondary"}
        Current view visibility: ${currentImageView?.visibility == View.VISIBLE}
        Next view visibility: ${nextImageView.visibility == View.VISIBLE}
    """.trimIndent())

        Glide.with(this)
            .load(url)
            .apply(RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .error(android.R.drawable.ic_dialog_alert))
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    updateDebugStatus("Failed to load image: ${e?.message}")
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    updateDebugStatus("Image loaded successfully")
                    return false
                }
            })
            .into(nextImageView)

        // Crossfade transition
        ObjectAnimator.ofFloat(nextImageView, View.ALPHA, 0f, 1f).apply {
            duration = TRANSITION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        currentImageView?.let { currentView ->
            ObjectAnimator.ofFloat(currentView, View.ALPHA, 1f, 0f).apply {
                duration = TRANSITION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
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

    private fun setupDreamService() {
        Log.e(TAG, "Setting up dream service - Thread: ${Thread.currentThread().name}")
        try {
            setContentView(R.layout.dream_layout)
            Log.e(TAG, "Content view set successfully")

            // Initialize views with detailed error checking
            initializeViews()
            setupImageViews()

            // Start dream sequence
            startDreamSequence()
        } catch (e: Exception) {
            Log.e(TAG, "Setup error: ${e.message}")
            e.printStackTrace()
            handleError(e)
        }
    }

    private fun initializeViews() {
        Log.e(TAG, "Initializing views")

        rootLayout = findViewById<FrameLayout>(R.id.dream_root_layout).also {
            Log.e(TAG, "Root layout found and initialized")
        } ?: throw IllegalStateException("Root layout not found")

        debugStatus = findViewById<TextView>(R.id.debugStatus).also {
            it.visibility = View.VISIBLE
            Log.e(TAG, "Debug status view initialized and made visible")
        } ?: throw IllegalStateException("Debug status view not found")

        // Similar pattern for other views...
        debugPhotoInfo = findViewById<TextView>(R.id.debugPhotoInfo).also {
            it.visibility = View.VISIBLE
        } ?: throw IllegalStateException("Debug photo info view not found")

        loadingIndicator = findViewById<ProgressBar>(R.id.loadingIndicator).also {
            it.visibility = View.VISIBLE
        } ?: throw IllegalStateException("Loading indicator not found")

        primaryImageView = findViewById<ImageView>(R.id.primaryImageView).also {
            it.visibility = View.VISIBLE
        } ?: throw IllegalStateException("Primary image view not found")

        secondaryImageView = findViewById<ImageView>(R.id.secondaryImageView).also {
            it.visibility = View.VISIBLE
        } ?: throw IllegalStateException("Secondary image view not found")
    }

    private fun startDreamSequence() {
        Log.e(TAG, "Starting dream sequence")
        coroutineScope.launch {
            try {
                verifyAndInitialize()
                Log.e(TAG, "Dream sequence started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting dream sequence", e)
                handleError(e)
            }
        }
    }

    private fun setupImageViews() {
        primaryImageView.scaleType = ImageView.ScaleType.CENTER_CROP
        secondaryImageView.scaleType = ImageView.ScaleType.CENTER_CROP
        secondaryImageView.alpha = 0f
        currentImageView = primaryImageView
    }

    private fun updateDebugStatus(status: String) {
        Log.e(TAG, status)
        coroutineScope.launch(Dispatchers.Main) {
            try {
                debugStatus.text = status
                debugStatus.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Error updating debug status", e)
            }
        }
    }

    private fun updatePhotoInfo(info: String) {
        Log.e(TAG, info)
        coroutineScope.launch(Dispatchers.Main) {
            try {
                debugPhotoInfo.text = info
                debugPhotoInfo.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e(TAG, "Error updating photo info", e)
            }
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
    private suspend fun refreshTokenUsingRefreshToken() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@PhotoDreamService)
                val refreshToken = prefs.getString("refresh_token", null)
                    ?: throw Exception("No refresh token available")

                val tokenEndpoint = "https://oauth2.googleapis.com/token"
                val clientId = getString(R.string.google_oauth_client_id)
                val clientSecret = getString(R.string.google_oauth_client_secret)

                val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
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

                    PreferenceManager.getDefaultSharedPreferences(this@PhotoDreamService)
                        .edit()
                        .putString("access_token", jsonResponse.getString("access_token"))
                        .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                        .apply()

                    logDebugInfo("Successfully refreshed access token using refresh token")
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    logDebugInfo("Failed to refresh token: $error")
                    throw Exception("Token refresh failed: ${connection.responseMessage}")
                }
            } catch (e: Exception) {
                logDebugInfo("Failed to refresh token: ${e.message}")
                throw e
            }
        }
    }

    private suspend fun refreshTokens() = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@PhotoDreamService)
            val refreshToken = prefs.getString("refresh_token", null)
                ?: throw Exception("No refresh token available")
            val clientId = getString(R.string.google_oauth_client_id)
            val clientSecret = getString(R.string.google_oauth_client_secret)

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

                PreferenceManager.getDefaultSharedPreferences(this@PhotoDreamService)
                    .edit()
                    .putString("access_token", jsonResponse.getString("access_token"))
                    .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                    .apply()

                true
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                Log.e(TAG, "Failed to refresh token: $error")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            false
        }
    }

    private suspend fun getOrRefreshCredentials(): GoogleCredentials? = withContext(Dispatchers.IO) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@PhotoDreamService)
            val accessToken = prefs.getString("access_token", null)
            val tokenExpiration = prefs.getLong("token_expiration", 0)

            // Check if token is expired or about to expire in the next minute
            if (accessToken == null || System.currentTimeMillis() > tokenExpiration - 60000) {
                if (!refreshTokens()) {
                    return@withContext null
                }
            }

            // Get the current access token
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

    private suspend fun initializePhotosLibraryClient() {
        withContext(Dispatchers.IO) {
            try {
                logDebugInfo("Initializing Photos Library Client")
                val account = GoogleSignIn.getLastSignedInAccount(this@PhotoDreamService)
                    ?: throw IllegalStateException("No Google account found")

                // Remove the serverAuthCode parameter here
                val credentials = getOrRefreshCredentials()  // Remove the parameter
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
                var errorCount = 0

                photosLibraryClient?.let { client ->
                    for (albumId in selectedAlbums) {
                        var retryAttempt = 0
                        while (retryAttempt < MAX_RETRIES) {
                            try {
                                logDebugInfo("Loading album: $albumId (attempt ${retryAttempt + 1})")
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
                                break // Success, exit retry loop
                            } catch (e: Exception) {
                                errorCount++
                                logDebugInfo("Error loading album $albumId: ${e.message}")
                                if (e.message?.contains("UNAUTHENTICATED") == true) {
                                    retryAttempt++
                                    if (retryAttempt < MAX_RETRIES) {
                                        val serverAuthCode = PreferenceManager
                                            .getDefaultSharedPreferences(this@PhotoDreamService)
                                            .getString("google_server_auth_code", null)

                                        if (serverAuthCode != null) {
                                            refreshAccessToken(serverAuthCode)
                                            delay(1000) // Wait a bit before retrying
                                            continue
                                        }
                                    }
                                }
                                break // Non-auth error or max retries reached
                            }
                        }
                    }
                }

                if (photoUrls.isNotEmpty()) {
                    photoUrls.shuffle()
                    logDebugInfo("Starting slideshow with ${photoUrls.size} photos")
                    withContext(Dispatchers.Main) {
                        startSlideshow()
                    }
                } else if (errorCount > 0) {
                    throw Exception("Failed to load photos due to authentication errors")
                } else {
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

                // First, exchange the server auth code for tokens
                val postData = StringBuilder()
                    .append("grant_type=authorization_code")
                    .append("&code=").append(serverAuthCode)
                    .append("&client_id=").append(clientId)
                    .append("&client_secret=").append(clientSecret)
                    .append("&redirect_uri=").append("urn:ietf:wg:oauth:2.0:oob")
                    .toString()

                connection.outputStream.use { it.write(postData.toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)

                    // Save all tokens
                    PreferenceManager.getDefaultSharedPreferences(this@PhotoDreamService)
                        .edit()
                        .putString("access_token", jsonResponse.getString("access_token"))
                        .putString("refresh_token", jsonResponse.getString("refresh_token"))
                        .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                        .apply()

                    logDebugInfo("Successfully exchanged auth code for tokens")
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    logDebugInfo("Failed to exchange auth code: $error")
                    throw Exception("Token exchange failed: ${connection.responseMessage}")
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

    private fun handleError(error: Exception) {
        val errorMessage = "Error: ${error.message}"
        Log.e(TAG, errorMessage, error)

        coroutineScope.launch(Dispatchers.Main) {
            try {
                updateDebugStatus(errorMessage)
                loadingIndicator.visibility = View.GONE
                debugStatus.setTextColor(Color.RED)

                Toast.makeText(
                    this@PhotoDreamService,
                    errorMessage,
                    Toast.LENGTH_LONG
                ).show()

                if (retryCount < MAX_RETRIES) {
                    handler.postDelayed({
                        retryCount++
                        verifyAndInitialize()
                    }, 5000)
                }

                dumpState()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling error", e)
            }
        }
    }

    override fun onDreamingStarted() {
        Log.e(TAG, "PhotoDreamService onDreamingStarted BEGIN")
        super.onDreamingStarted()
        try {
            if (!::debugStatus.isInitialized) {
                Log.e(TAG, "Debug status not initialized!")
            }
            if (!::primaryImageView.isInitialized) {
                Log.e(TAG, "Primary image view not initialized!")
            }
            logDebugInfo("Dream started - checking state")
            dumpState()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDreamingStarted", e)
        }
        Log.e(TAG, "PhotoDreamService onDreamingStarted END")
    }

    override fun onDreamingStopped() {
        Log.e(TAG, "PhotoDreamService onDreamingStopped BEGIN")
        super.onDreamingStopped()
        logDebugInfo("onDreamingStopped")
        cleanup()
        Log.e(TAG, "PhotoDreamService onDreamingStopped END")
    }

    override fun onDetachedFromWindow() {
        Log.e(TAG, "PhotoDreamService onDetachedFromWindow BEGIN")
        super.onDetachedFromWindow()
        logDebugInfo("onDetachedFromWindow")
        cleanup()
        Log.e(TAG, "PhotoDreamService onDetachedFromWindow END")
    }

    private fun cleanup() {
        Log.e(TAG, "Starting cleanup")
        logDebugInfo("Cleaning up resources")
        slideshowRunnable?.let {
            handler.removeCallbacks(it)
            Log.e(TAG, "Removed slideshow callbacks")
        }
        coroutineScope.cancel()
        Log.e(TAG, "Cancelled coroutine scope")
        photosLibraryClient?.shutdown()
        Log.e(TAG, "Shut down photos library client")
    }
}