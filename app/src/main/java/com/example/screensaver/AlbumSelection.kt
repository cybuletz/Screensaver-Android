package com.example.screensaver

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.screensaver.adapters.AlbumAdapter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.photos.library.v1.PhotosLibraryClient
import com.google.photos.library.v1.PhotosLibrarySettings
import com.google.photos.types.Album
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumAdapter: AlbumAdapter
    private var photosLibraryClient: PhotosLibraryClient? = null

    companion object {
        private const val TAG = "AlbumSelection"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album_selection)

        recyclerView = findViewById(R.id.albumRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        albumAdapter = AlbumAdapter { album ->
            toggleAlbumSelection(album)
        }
        recyclerView.adapter = albumAdapter

        setupPhotosLibraryClient()
    }

    private fun setupPhotosLibraryClient() {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            try {
                val settings = PhotosLibrarySettings.newBuilder()
                    .setCredentials(account.account)
                    .build()
                photosLibraryClient = PhotosLibraryClient.initialize(settings)
                loadAlbums()
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up Photos Library client", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Please sign in with Google first", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadAlbums() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val albums = mutableListOf<Album>()
                photosLibraryClient?.listAlbums()?.iterateAll()?.forEach { album ->
                    albums.add(album)
                }

                withContext(Dispatchers.Main) {
                    albumAdapter.submitList(albums)
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
            selectedAlbums.remove(album.id)
        } else {
            selectedAlbums.add(album.id)
        }

        prefs.edit().putStringSet("selected_albums", selectedAlbums).apply()
        albumAdapter.notifyItemChanged(albumAdapter.currentList.indexOf(album))
    }
}