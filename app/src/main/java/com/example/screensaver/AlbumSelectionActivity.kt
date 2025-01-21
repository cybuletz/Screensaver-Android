package com.example.screensaver

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit

class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumAdapter: AlbumAdapter
    private var photosLibraryClient: PhotosLibraryClient? = null
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val TAG = "AlbumSelection"
        private const val RC_SIGN_IN = 9001
        private val REQUIRED_SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album_selection)

        setupRecyclerView()
        setupGoogleSignIn()
        checkGoogleSignIn()
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.albumRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        albumAdapter = AlbumAdapter { album ->
            toggleAlbumSelection(album)
        }
        recyclerView.adapter = albumAdapter
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestId()
            .apply {
                REQUIRED_SCOPES.forEach { scope ->
                    requestScopes(scope)
                }
            }
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun checkGoogleSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null && !account.isExpired) {
            val hasRequiredScopes = GoogleSignIn.hasPermissions(account, *REQUIRED_SCOPES.toTypedArray())
            if (hasRequiredScopes) {
                setupPhotosLibraryClient(account.idToken)
            } else {
                requestGoogleSignIn()
            }
        } else {
            requestGoogleSignIn()
        }
    }

    private fun requestGoogleSignIn() {
        startActivityForResult(googleSignInClient.signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                setupPhotosLibraryClient(account?.idToken)
            } catch (e: ApiException) {
                Log.e(TAG, "Sign in failed: ${e.message}", e)
                Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun setupPhotosLibraryClient(idToken: String?) {
        if (idToken == null) {
            Log.e(TAG, "No ID token available")
            Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            val expirationDate = Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
            val accessToken = AccessToken.newBuilder()
                .setTokenValue(idToken)
                .setExpirationTime(expirationDate)
                .build()

            val credentials = OAuth2Credentials.create(accessToken)

            val settings = PhotosLibrarySettings.newBuilder()
                .setCredentialsProvider { credentials }
                .build()

            photosLibraryClient = PhotosLibraryClient.initialize(settings)
            loadAlbums()

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Photos Library client", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadAlbums() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val albums = mutableListOf<Album>()
                photosLibraryClient?.listAlbums()?.iterateAll()?.forEach { googleAlbum ->
                    Log.d(TAG, "Found album: ${googleAlbum.title}")
                    albums.add(Album(
                        id = googleAlbum.id,
                        title = googleAlbum.title,
                        coverPhotoUrl = googleAlbum.coverPhotoBaseUrl,
                        mediaItemsCount = googleAlbum.mediaItemsCount.toInt()
                    ))
                }

                withContext(Dispatchers.Main) {
                    if (albums.isEmpty()) {
                        Log.w(TAG, "No albums found")
                        Toast.makeText(this@AlbumSelectionActivity,
                            "No albums found",
                            Toast.LENGTH_SHORT).show()
                    } else {
                        Log.d(TAG, "Loaded ${albums.size} albums")
                        albumAdapter.submitList(albums)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading albums", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AlbumSelectionActivity,
                        "Error loading albums: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleAlbumSelection(album: Album) {
        val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
        val selectedAlbums = prefs.getStringSet("selected_albums", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

        if (selectedAlbums.contains(album.id)) {
            Log.d(TAG, "Removing album from selection: ${album.title}")
            selectedAlbums.remove(album.id)
        } else {
            Log.d(TAG, "Adding album to selection: ${album.title}")
            selectedAlbums.add(album.id)
        }

        prefs.edit()
            .putStringSet("selected_albums", selectedAlbums)
            .apply()

        albumAdapter.notifyItemChanged(albumAdapter.currentList.indexOf(album))

        val message = if (selectedAlbums.contains(album.id))
            "Added ${album.title}" else "Removed ${album.title}"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        photosLibraryClient?.shutdown()
    }
}