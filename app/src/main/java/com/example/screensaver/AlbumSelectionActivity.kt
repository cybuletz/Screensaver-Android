package com.example.screensaver

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.screensaver.adapters.AlbumAdapter
import com.example.screensaver.models.Album
import com.example.screensaver.utils.DreamServiceHelper
import com.example.screensaver.utils.DreamServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.example.screensaver.model.Album

class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var confirmButton: Button
    private lateinit var dreamServiceHelper: DreamServiceHelper
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val photoManager by lazy { GooglePhotosManager.getInstance(this) }
    private var isLoading = false

    companion object {
        private const val TAG = "AlbumSelection"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logd("onCreate called")
        setContentView(R.layout.activity_album_selection)

        dreamServiceHelper = DreamServiceHelper.create(this, PhotoDreamService::class.java)

        try {
            setupRecyclerView()
            setupConfirmButton()
            initializeGooglePhotos()
        } catch (e: Exception) {
            loge("Error in onCreate", e)
            Toast.makeText(this, "Failed to initialize: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initializeGooglePhotos() {
        coroutineScope.launch {
            try {
                setLoading(true)
                if (photoManager.initialize()) {
                    loadAlbums()
                    checkDreamServiceRegistration()
                } else {
                    showError("Failed to initialize Google Photos")
                    showRetryButton()
                }
            } catch (e: Exception) {
                showError("Error initializing Google Photos: ${e.message}")
                showRetryButton()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showRetryButton() {
        findViewById<Button>(R.id.retryButton)?.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                visibility = View.GONE
                initializeGooglePhotos()
            }
        }
    }

    private suspend fun loadAlbums() {
        try {
            setLoading(true)
            val albums = photoManager.getAlbums()
            val selectedAlbumIds = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
                .getStringSet("selected_albums", emptySet()) ?: emptySet()

            val albumModels = albums.map { googleAlbum ->
                Album(
                    id = googleAlbum.id,
                    title = googleAlbum.title,
                    coverPhotoUrl = googleAlbum.coverPhotoBaseUrl,
                    mediaItemsCount = googleAlbum.mediaItemsCount.toInt(),
                    isSelected = selectedAlbumIds.contains(googleAlbum.id)
                )
            }

            withContext(Dispatchers.Main) {
                if (albumModels.isEmpty()) {
                    showError("No albums found")
                } else {
                    albumAdapter.submitList(albumModels)
                    updateConfirmButtonState()
                }
            }
        } catch (e: Exception) {
            showError("Error loading albums: ${e.message}")
            showRetryButton()
        } finally {
            setLoading(false)
        }
    }

    private fun setupRecyclerView() {
        logd("Setting up RecyclerView")
        recyclerView = findViewById(R.id.albumRecyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        albumAdapter = AlbumAdapter { album ->
            if (!isLoading) {
                logd("Album clicked: ${album.title}")
                toggleAlbumSelection(album)
            }
        }
        recyclerView.adapter = albumAdapter
    }

    private fun setupConfirmButton() {
        logd("Setting up Confirm Button")
        confirmButton = findViewById(R.id.confirmButton)

        confirmButton.setOnClickListener {
            if (!isLoading) {
                logd("Confirm button clicked")
                setResult(Activity.RESULT_OK)
                finish()
            }
        }

        updateConfirmButtonState()
    }

    private fun updateConfirmButtonState() {
        val selectedAlbums = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
            .getStringSet("selected_albums", emptySet()) ?: emptySet()
        confirmButton.isEnabled = selectedAlbums.isNotEmpty() && !isLoading
    }

    private fun toggleAlbumSelection(album: Album) {
        if (isLoading) return

        logd("Toggling selection for album: ${album.title}")
        val prefs = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
        val selectedAlbums = prefs.getStringSet("selected_albums", mutableSetOf())?.toMutableSet()
            ?: mutableSetOf()

        if (selectedAlbums.contains(album.id)) {
            selectedAlbums.remove(album.id)
            album.isSelected = false
        } else {
            selectedAlbums.add(album.id)
            album.isSelected = true
        }

        prefs.edit()
            .putStringSet("selected_albums", selectedAlbums)
            .apply()

        albumAdapter.notifyItemChanged(albumAdapter.currentList.indexOf(album))
        updateConfirmButtonState()

        Toast.makeText(
            this,
            if (album.isSelected) "Added ${album.title}" else "Removed ${album.title}",
            Toast.LENGTH_SHORT
        ).show()

        checkDreamServiceRegistration()
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
            Log.e(TAG, "Error starting dream", e)
            Toast.makeText(this, "Error starting dream: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLoading(show: Boolean) {
        findViewById<View>(R.id.loadingProgress)?.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        isLoading = loading
        showLoading(loading)
        confirmButton.isEnabled = !loading && (albumAdapter.currentList.any { it.isSelected })
    }

    private fun showError(message: String) {
        logd(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun logd(message: String) = Log.d(TAG, message)
    private fun loge(message: String, e: Throwable? = null) = Log.e(TAG, message, e)

    override fun onDestroy() {
        super.onDestroy()
        logd("onDestroy called")
        coroutineScope.cancel()
        photoManager.cleanup()
    }
}