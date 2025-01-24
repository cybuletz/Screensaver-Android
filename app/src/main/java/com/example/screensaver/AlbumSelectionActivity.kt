package com.example.screensaver

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.screensaver.adapters.AlbumAdapter
import com.example.screensaver.models.Album
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.DreamServiceHelper
import com.example.screensaver.utils.DreamServiceStatus
import com.example.screensaver.utils.PhotoLoadingManager  // Add this import
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.example.screensaver.models.MediaItem
import androidx.activity.viewModels
import com.example.screensaver.databinding.ActivityAlbumSelectionBinding
import com.example.screensaver.PhotoDreamService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import androidx.preference.PreferenceManager


@AndroidEntryPoint
class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlbumSelectionBinding
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var dreamServiceHelper: DreamServiceHelper
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    @Inject
    lateinit var photoManager: GooglePhotosManager

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    private var isLoading = false
    private val viewModel: AlbumSelectionViewModel by viewModels()

    companion object {
        private const val TAG = "AlbumSelection"
        private const val PRECACHE_COUNT = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupConfirmButton()

        lifecycleScope.launch {
            viewModel.isLoading.collect { isLoading ->
                setLoading(isLoading)
            }
        }

        try {
            initializeGooglePhotos()
        } catch (e: Exception) {
            loge("Error in onCreate", e)
            Toast.makeText(this, getString(R.string.init_failed, e.message), Toast.LENGTH_LONG).show()
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
                    showError(getString(R.string.google_photos_init_failed))
                    showRetryButton()
                }
            } catch (e: Exception) {
                showError(getString(R.string.google_photos_init_error, e.message))
                showRetryButton()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showRetryButton() {
        binding.retryButton.apply {
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
            val selectedAlbumIds = PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet("selected_albums", emptySet()) ?: emptySet()

            withContext(Dispatchers.Main) {
                val albumModels = albums.map { googleAlbum ->
                    Album(
                        id = googleAlbum.id,
                        title = googleAlbum.title,
                        coverPhotoUrl = googleAlbum.coverPhotoUrl ?: "", // Changed from coverPhotoMediaItemId
                        mediaItemsCount = googleAlbum.mediaItemsCount.toInt(),
                        isSelected = selectedAlbumIds.contains(googleAlbum.id)
                    )
                }

                if (albumModels.isEmpty()) {
                    showError(getString(R.string.no_albums_found))
                } else {
                    albumAdapter.submitList(albumModels)
                    updateConfirmButtonState()
                }
            }
        } catch (e: Exception) {
            showError(getString(R.string.albums_load_error, e.message))
            showRetryButton()
        } finally {
            setLoading(false)
        }
    }

    private fun setupRecyclerView() {
        binding.albumRecyclerView.layoutManager = GridLayoutManager(this, 2)
        albumAdapter = AlbumAdapter { album ->
            if (!viewModel.isLoading.value) {
                toggleAlbumSelection(album)
            }
        }
        binding.albumRecyclerView.adapter = albumAdapter
    }

    private suspend fun precachePhotos() {
        withContext(Dispatchers.IO) {
            try {
                val photoCount = photoManager.getPhotoCount()
                val countToPreload = if (photoCount > 0) {
                    minOf(PRECACHE_COUNT, photoCount)
                } else {
                    0
                }

                if (countToPreload > 0) {
                    repeat(countToPreload) { index ->
                        photoManager.getPhotoUrl(index)?.let { url ->
                            val mediaItem = MediaItem(
                                id = index.toString(),
                                albumId = "lock_screen",
                                baseUrl = url,
                                mimeType = "image/jpeg",
                                width = 1920,
                                height = 1080
                            )
                            photoLoadingManager.preloadPhoto(mediaItem)
                        }
                    }
                    Log.d(TAG, "Precached $countToPreload photos")
                } else {
                    Log.d(TAG, "No photos to precache")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error precaching photos", e)
            }
        }
    }

    private fun setupConfirmButton() {
        logd("Setting up Confirm Button")
        binding.confirmButton.setOnClickListener {
            if (!isLoading) {
                logd("Confirm button clicked")
                saveSelectedAlbums()
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
        updateConfirmButtonState()
    }

    private fun saveSelectedAlbums() {
        val selectedAlbums = albumAdapter.currentList
            .filter { it.isSelected }
            .map { it.id }
            .toSet()

        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putStringSet("selected_albums", selectedAlbums)
            .apply()
    }

    private fun updateConfirmButtonState() {
        binding.confirmButton.isEnabled = albumAdapter.currentList.any { it.isSelected } && !isLoading
    }

    private fun toggleAlbumSelection(album: Album) {
        if (isLoading) return

        logd("Toggling selection for album: ${album.title}")
        val updatedAlbum = album.copy(isSelected = !album.isSelected)
        val currentList = albumAdapter.currentList.toMutableList()
        val position = currentList.indexOfFirst { it.id == album.id }

        if (position != -1) {
            currentList[position] = updatedAlbum
            albumAdapter.submitList(currentList)
            updateConfirmButtonState()

            Toast.makeText(
                this,
                getString(
                    if (updatedAlbum.isSelected) R.string.album_added else R.string.album_removed,
                    updatedAlbum.title
                ),
                Toast.LENGTH_SHORT
            ).show()

            checkDreamServiceRegistration()
        }
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
        binding.testDreamButton.apply {
            visibility = View.VISIBLE
            setOnClickListener {
                testDreamService()
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
        binding.loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
        binding.albumRecyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingContainer.visibility = if (loading) View.VISIBLE else View.GONE
        binding.albumRecyclerView.visibility = if (loading) View.GONE else View.VISIBLE
        binding.confirmButton.isEnabled = !loading && albumAdapter.currentList.any { it.isSelected }
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