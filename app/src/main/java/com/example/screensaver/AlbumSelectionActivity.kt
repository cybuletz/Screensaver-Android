package com.example.screensaver

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.example.screensaver.adapters.AlbumAdapter
import com.example.screensaver.models.Album
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import kotlinx.coroutines.*
import java.util.Date
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import android.content.ComponentName
import android.content.Context
import com.example.screensaver.utils.DreamServiceHelper
import com.example.screensaver.utils.DreamServiceStatus
private lateinit var signInButton: SignInButton
private lateinit var signInContainer: LinearLayout

class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var confirmButton: Button
    private var photosLibraryClient: PhotosLibraryClient? = null
    private lateinit var googleSignInClient: GoogleSignInClient
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var dreamServiceHelper: DreamServiceHelper

    companion object {
        private const val TAG = "AlbumSelection"
        private const val RC_SIGN_IN = 9001

        private val REQUIRED_SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly"),
            Scope("https://www.googleapis.com/auth/photoslibrary"),
            Scope("https://www.googleapis.com/auth/photoslibrary.sharing")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logd("onCreate called")
        setContentView(R.layout.activity_album_selection)

        // Initialize DreamServiceHelper with the create method
        dreamServiceHelper = DreamServiceHelper.create(this, PhotoDreamService::class.java)

        try {
            initializeViews()  // New method
            setupRecyclerView()
            setupConfirmButton()
            setupGoogleSignIn()
            checkDreamServiceRegistration()
        } catch (e: Exception) {
            loge("Error in onCreate", e)
            Toast.makeText(this, "Failed to initialize: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initializeViews() {
        signInButton = findViewById(R.id.signInButton)
        signInContainer = findViewById(R.id.signInContainer)

        // Setup sign in button
        signInButton.setSize(SignInButton.SIZE_WIDE)
        signInButton.setOnClickListener {
            requestGoogleSignIn()
        }
    }

    private fun updateUIState(isSignedIn: Boolean) {
        signInContainer.visibility = if (isSignedIn) View.GONE else View.VISIBLE
        recyclerView.visibility = if (isSignedIn) View.VISIBLE else View.GONE
    }

    private fun checkDreamServiceRegistration() {
        try {
            logd("Checking Dream Service availability...")
            when (dreamServiceHelper.getDreamServiceStatus()) {
                DreamServiceStatus.API_UNAVAILABLE -> {
                    loge("Dream API not available on this device")
                    Toast.makeText(this, "Screensaver not supported on this device", Toast.LENGTH_LONG).show()
                }
                DreamServiceStatus.NOT_SELECTED -> {
                    logd("Dream service not selected in system settings")
                    Toast.makeText(this, "Please enable screensaver in system settings", Toast.LENGTH_LONG).show()
                    dreamServiceHelper.openDreamSettings()
                }
                DreamServiceStatus.CONFIGURED -> {
                    logd("Dream service is configured")
                    addTestDreamButton()
                }
                DreamServiceStatus.ACTIVE -> {
                    logd("Dream service is active")
                    addTestDreamButton()
                }
                DreamServiceStatus.UNKNOWN -> {
                    loge("Dream service status unknown")
                }
            }
        } catch (e: Exception) {
            loge("Error checking dream service", e)
        }
    }

    private fun addTestDreamButton() {
        val testButton = Button(this).apply {
            text = "Test Dream Service"
            setOnClickListener {
                testDreamService()
            }
        }

        findViewById<View>(R.id.root_layout)?.let { root ->
            if (root is android.view.ViewGroup) {
                root.addView(testButton)
            }
        }
    }

    private fun testDreamService() {
        try {
            when (dreamServiceHelper.getDreamServiceStatus()) {
                DreamServiceStatus.API_UNAVAILABLE -> {
                    Toast.makeText(this, "Screensaver not supported on this device", Toast.LENGTH_SHORT).show()
                }
                DreamServiceStatus.NOT_SELECTED -> {
                    Toast.makeText(this, "Please enable screensaver in system settings", Toast.LENGTH_SHORT).show()
                    dreamServiceHelper.openDreamSettings()
                }
                else -> {
                    dreamServiceHelper.openDreamSettings()
                    Toast.makeText(this, "Opening screensaver settings", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("DreamService", "Error starting dream", e)
            Toast.makeText(this, "Error starting dream: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerView() {
        logd("Setting up RecyclerView")
        recyclerView = findViewById(R.id.albumRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        albumAdapter = AlbumAdapter { album ->
            logd("Album clicked: ${album.title}")
            toggleAlbumSelection(album)
        }
        recyclerView.adapter = albumAdapter
        logd("RecyclerView setup completed")
    }

    private fun setupConfirmButton() {
        logd("Setting up Confirm Button")
        confirmButton = findViewById(R.id.confirmButton)

        confirmButton.setOnClickListener {
            logd("Confirm button clicked")
            setResult(Activity.RESULT_OK)
            finish()
        }

        val selectedAlbums = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
            .getStringSet("selected_albums", emptySet()) ?: emptySet()
        logd("Retrieved ${selectedAlbums.size} previously selected albums")
        confirmButton.isEnabled = selectedAlbums.isNotEmpty()
    }

    private fun setupGoogleSignIn() {
        val clientId = getString(R.string.google_oauth_client_id)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestId()
            .requestServerAuthCode(clientId, false)  // Changed force to false
            .requestIdToken(clientId)
            .apply {
                REQUIRED_SCOPES.forEach { scope ->
                    requestScopes(scope)
                }
            }
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
        checkGoogleSignIn()
    }

    private fun checkGoogleSignIn() {
        val account = loadPersistedSignInState()
        if (account != null && !account.isExpired) {
            val hasRequiredScopes = GoogleSignIn.hasPermissions(account, *REQUIRED_SCOPES.toTypedArray())
            if (hasRequiredScopes) {
                updateUIState(true)
                setupPhotosLibraryClient(account.idToken)
            } else {
                updateUIState(false)
                requestGoogleSignIn()
            }
        } else {
            updateUIState(false)
            requestGoogleSignIn()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        logd("onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        if (requestCode == RC_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                account?.let { acc ->
                    persistSignInState(acc)
                    acc.idToken?.let { token ->
                        setupPhotosLibraryClient(token)
                    } ?: run {
                        loge("No ID token available")
                        Toast.makeText(this, "Authentication failed: No token available", Toast.LENGTH_SHORT).show()
                        requestGoogleSignIn()
                    }
                } ?: run {
                    loge("Sign in failed: Account is null")
                    Toast.makeText(this, "Sign in failed: Could not get account", Toast.LENGTH_SHORT).show()
                    requestGoogleSignIn()
                }
            } catch (e: ApiException) {
                loge("Sign in failed with error code: ${e.statusCode}", e)
                handleSignInError(e)
            }
        }
    }

    private fun persistSignInState(account: GoogleSignInAccount) {
        val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
        prefs.edit()
            .putString("google_account_email", account.email)
            .putString("google_account_id", account.id)
            .putString("access_token", account.idToken)
            .putString("server_auth_code", account.serverAuthCode)
            .apply()
    }

    private fun loadPersistedSignInState(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(this)
    }

    private fun requestGoogleSignIn() {
        logd("Requesting Google Sign In")
        try {
            startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
        } catch (e: Exception) {
            loge("Error starting sign in activity", e)
            Toast.makeText(this, "Failed to start sign in: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleSignInIntent(data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            logd("Sign in successful for: ${account.email}")
            logd("Has ID token: ${account.idToken != null}")
            logd("Has server auth code: ${account.serverAuthCode != null}")

            if (account.idToken != null) {
                handleSignInResult(account)
            } else {
                loge("No ID token available after sign in")
                googleSignInClient.signOut().addOnCompleteListener {
                    setupGoogleSignIn()
                }
            }
        } catch (e: ApiException) {
            handleSignInError(e)
        }
    }

    private fun handleSignInError(e: ApiException) {
        when (e.statusCode) {
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> {
                logd("Sign in cancelled by user")
                finish()
            }
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> {
                loge("Sign in failed", e)
                setupGoogleSignIn()
            }
            GoogleSignInStatusCodes.DEVELOPER_ERROR -> {
                loge("Developer error - check OAuth configuration", e)
                Toast.makeText(this,
                    "Authentication configuration error. Please check setup.",
                    Toast.LENGTH_LONG).show()
                finish()
            }
            else -> {
                loge("Sign in failed with status code: ${e.statusCode}", e)
                Toast.makeText(this,
                    "Sign in failed: ${e.message}",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleSignInResult(account: GoogleSignInAccount) {
        logd("Handling sign in result for account: ${account.email}")

        if (!GoogleSignIn.hasPermissions(account, *REQUIRED_SCOPES.toTypedArray())) {
            logd("Requesting additional scopes")
            GoogleSignIn.requestPermissions(
                this,
                RC_SIGN_IN,
                account,
                *REQUIRED_SCOPES.toTypedArray()
            )
            return
        }

        account.idToken?.let { token ->
            logd("Got ID token, setting up Photos Library Client")
            setupPhotosLibraryClient(token)
        } ?: run {
            loge("No ID token available")
            googleSignInClient.signOut().addOnCompleteListener {
                requestGoogleSignIn()
            }
        }
    }

    private fun setupPhotosLibraryClient(idToken: String?) {
        if (idToken == null) {
            loge("ID token is null")
            Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            requestGoogleSignIn()
            return
        }

        logd("Setting up Photos Library Client")
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(this@AlbumSelectionActivity)
                    ?: throw Exception("No signed in account found")

                val serverAuthCode = account.serverAuthCode
                    ?: throw Exception("No server auth code available")

                val accessToken = exchangeAuthCodeForAccessToken(serverAuthCode)
                val credentials = OAuth2Credentials.create(accessToken)
                val settings = PhotosLibrarySettings.newBuilder()
                    .setCredentialsProvider { credentials }
                    .build()

                withContext(Dispatchers.Main) {
                    showLoading(true)
                }

                photosLibraryClient = PhotosLibraryClient.initialize(settings)
                logd("Photos Library Client initialized successfully")
                loadAlbums()
            } catch (e: Exception) {
                loge("Error setting up Photos Library client", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AlbumSelectionActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    private fun exchangeAuthCodeForAccessToken(serverAuthCode: String): AccessToken {
        val tokenEndpoint = "https://oauth2.googleapis.com/token"
        val clientId = getString(R.string.google_oauth_client_id)
        val clientSecret = getString(R.string.google_oauth_client_secret)

        val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        // Changed redirect_uri to match what we set in the OAuth console
        val postData = "code=$serverAuthCode" +
                "&client_id=$clientId" +
                "&client_secret=$clientSecret" +
                "&grant_type=authorization_code" +
                "&redirect_uri=http://localhost"  // Changed from urn:ietf:wg:oauth:2.0:oob to http://localhost

        try {
            connection.outputStream.use { it.write(postData.toByteArray()) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                loge("Token exchange failed: $errorStream")
                throw Exception("Failed to get access token: ${connection.responseMessage}")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val jsonResponse = JSONObject(response)
            val accessTokenString = jsonResponse.getString("access_token")
            val expiresIn = jsonResponse.getLong("expires_in")

            // Save the tokens for future use
            getSharedPreferences("screensaver_prefs", MODE_PRIVATE).edit()
                .putString("access_token", accessTokenString)
                .putLong("token_expiration", System.currentTimeMillis() + (expiresIn * 1000))
                .apply()

            return AccessToken.newBuilder()
                .setTokenValue(accessTokenString)
                .setExpirationTime(Date(System.currentTimeMillis() + (expiresIn * 1000)))
                .build()
        } catch (e: Exception) {
            loge("Error exchanging auth code for access token", e)
            throw e
        }
    }

    private fun showLoading(show: Boolean) {
        logd("Showing loading state: $show")
        findViewById<View>(R.id.loadingProgress)?.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun loadAlbums() {
        logd("Starting to load albums")
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val albums = mutableListOf<Album>()
                val selectedAlbums = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
                    .getStringSet("selected_albums", emptySet()) ?: emptySet()
                logd("Retrieved ${selectedAlbums.size} previously selected albums")

                photosLibraryClient?.let { client ->
                    logd("Fetching albums from Photos Library")
                    client.listAlbums().iterateAll().forEach { googleAlbum ->
                        logd("Processing album: ${googleAlbum.title}")
                        albums.add(Album(
                            id = googleAlbum.id,
                            title = googleAlbum.title,
                            coverPhotoUrl = googleAlbum.coverPhotoBaseUrl,
                            mediaItemsCount = googleAlbum.mediaItemsCount.toInt(),
                            isSelected = selectedAlbums.contains(googleAlbum.id)
                        ))
                    }
                } ?: throw IllegalStateException("PhotosLibraryClient is null")

                withContext(Dispatchers.Main) {
                    showLoading(false)
                    if (albums.isEmpty()) {
                        Toast.makeText(this@AlbumSelectionActivity,
                            "No albums found", Toast.LENGTH_SHORT).show()
                    } else {
                        albumAdapter.submitList(albums)
                    }
                }
            } catch (e: Exception) {
                loge("Error loading albums", e)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@AlbumSelectionActivity,
                        "Error loading albums: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleAlbumSelection(album: Album) {
        logd("Toggling selection for album: ${album.title}")
        val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
        val selectedAlbums = prefs.getStringSet("selected_albums", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

        if (selectedAlbums.contains(album.id)) {
            logd("Removing album from selection: ${album.title}")
            selectedAlbums.remove(album.id)
        } else {
            logd("Adding album to selection: ${album.title}")
            selectedAlbums.add(album.id)
        }

        prefs.edit()
            .putStringSet("selected_albums", selectedAlbums)
            .apply()

        album.isSelected = selectedAlbums.contains(album.id)
        albumAdapter.notifyItemChanged(albumAdapter.currentList.indexOf(album))
        confirmButton.isEnabled = selectedAlbums.isNotEmpty()

        Toast.makeText(
            this,
            if (album.isSelected) "Added ${album.title}" else "Removed ${album.title}",
            Toast.LENGTH_SHORT
        ).show()

        // After toggling selection, check dream service status
        checkDreamServiceRegistration()
    }

    private fun logd(message: String) = Log.d(TAG, message)
    private fun loge(message: String, e: Throwable? = null) = Log.e(TAG, message, e)

    override fun onDestroy() {
        super.onDestroy()
        logd("onDestroy called")
        checkDreamServiceRegistration()
        coroutineScope.cancel()
        photosLibraryClient?.shutdown()
    }
}