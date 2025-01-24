package com.example.screensaver

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.example.screensaver.adapters.AlbumAdapter
import com.example.screensaver.databinding.ActivityAlbumSelectionBinding
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.DreamServiceHelper
import com.example.screensaver.utils.DreamServiceStatus
import com.example.screensaver.utils.PhotoLoadingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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

    private val viewModel: AlbumSelectionViewModel by viewModels()

    companion object {
        private const val TAG = "AlbumSelection"
        private const val PRECACHE_COUNT = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dreamServiceHelper = DreamServiceHelper.create(this, PhotoDreamService::class.java)

        setupViews()
        observeViewModel()
        initializeGooglePhotos()
    }

    private fun setupViews() {
        setupRecyclerView()
        setupConfirmButton()
        setupRetryButton()
    }

    private fun setupRecyclerView() {
        albumAdapter = AlbumAdapter { album ->
            if (!viewModel.isLoading.value) {  // Remove Elvis operator since value is non-null
                toggleAlbumSelection(album)
            }
        }

        binding.albumRecyclerView.apply {
            layoutManager = GridLayoutManager(this@AlbumSelectionActivity, 2)
            adapter = albumAdapter
        }
    }

    private fun setupConfirmButton() {
        binding.confirmButton.apply {
            isEnabled = false
            setOnClickListener {
                if (!viewModel.isLoading.value) {  // Remove Elvis operator since value is non-null
                    saveSelectedAlbums()
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun setupRetryButton() {
        binding.retryButton.setOnClickListener {
            it.visibility = View.GONE
            initializeGooglePhotos()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { isLoading ->
                updateLoadingState(isLoading)
            }
        }
    }

    private fun initializeGooglePhotos() {
        coroutineScope.launch {
            try {
                viewModel.setLoading(true)
                if (photoManager.initialize()) {
                    loadAlbums()
                    checkDreamServiceRegistration()
                } else {
                    handleError(getString(R.string.google_photos_init_failed))
                }
            } catch (e: Exception) {
                handleError(getString(R.string.google_photos_init_error, e.message))
            } finally {
                viewModel.setLoading(false)
            }
        }
    }

    private suspend fun loadAlbums() {
        try {
            viewModel.setLoading(true)
            val albums = photoManager.getAlbums()
            val selectedAlbumIds = PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet("selected_albums", emptySet()) ?: emptySet()

            withContext(Dispatchers.Main) {
                val albumModels = albums.map { googleAlbum ->
                    Album(
                        id = googleAlbum.id,
                        title = googleAlbum.title,
                        coverPhotoUrl = googleAlbum.coverPhotoUrl ?: "",
                        mediaItemsCount = googleAlbum.mediaItemsCount.toInt(),
                        isSelected = selectedAlbumIds.contains(googleAlbum.id)
                    )
                }

                if (albumModels.isEmpty()) {
                    handleError(getString(R.string.no_albums_found))
                } else {
                    albumAdapter.submitList(albumModels)
                    updateConfirmButtonState()
                }
            }
        } catch (e: Exception) {
            handleError(getString(R.string.albums_load_error, e.message))
        } finally {
            viewModel.setLoading(false)
        }
    }

    private fun toggleAlbumSelection(album: Album) {
        val updatedAlbum = album.copy(isSelected = !album.isSelected)
        val currentList = albumAdapter.currentList.toMutableList()
        val position = currentList.indexOfFirst { it.id == album.id }

        if (position != -1) {
            currentList[position] = updatedAlbum
            albumAdapter.submitList(currentList)
            updateConfirmButtonState()
            showSelectionToast(updatedAlbum)
        }
    }

    private fun showSelectionToast(album: Album) {
        val stringResId = if (album.isSelected) R.string.album_added else R.string.album_removed
        Toast.makeText(
            this,
            getString(stringResId, album.title),
            Toast.LENGTH_SHORT
        ).show()
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

        coroutineScope.launch {
            precachePhotos()
        }
    }

    private suspend fun precachePhotos() {
        withContext(Dispatchers.IO) {
            try {
                val photoCount = photoManager.getPhotoCount()
                val countToPreload = minOf(PRECACHE_COUNT, photoCount)

                when {
                    countToPreload > 0 -> {
                        repeat(countToPreload) { index ->
                            photoManager.getPhotoUrl(index)?.let { url ->
                                photoLoadingManager.preloadPhoto(
                                    MediaItem(
                                        id = index.toString(),
                                        albumId = "lock_screen",
                                        baseUrl = url,
                                        mimeType = "image/jpeg",
                                        width = 1920,
                                        height = 1080
                                    )
                                )
                            }
                        }
                        Log.d(TAG, "Precached $countToPreload photos")
                    }
                    else -> {
                        Log.d(TAG, "No photos to precache")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error precaching photos", e)
            }
        }
    }

    private fun checkDreamServiceRegistration() {
        when (dreamServiceHelper.getDreamServiceStatus()) {
            DreamServiceStatus.API_UNAVAILABLE -> {
                showError(getString(R.string.screensaver_not_supported))
            }
            DreamServiceStatus.NOT_SELECTED -> {
                showError(getString(R.string.enable_screensaver))
                dreamServiceHelper.openDreamSettings()
            }
            DreamServiceStatus.CONFIGURED,
            DreamServiceStatus.ACTIVE -> {
                Log.d(TAG, "Dream service is properly configured")
            }
            DreamServiceStatus.UNKNOWN -> {
                Log.e(TAG, "Dream service status unknown")
            }
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        binding.apply {
            val visibility = if (isLoading) View.VISIBLE else View.GONE
            val inverseVisibility = if (isLoading) View.GONE else View.VISIBLE

            loadingContainer.visibility = visibility
            albumRecyclerView.visibility = inverseVisibility
            confirmButton.isEnabled = !isLoading && albumAdapter.currentList.any { it.isSelected }
        }
    }

    private fun updateConfirmButtonState() {
        val isEnabled = !viewModel.isLoading.value &&  // Remove Elvis operator since value is non-null
                albumAdapter.currentList.any { it.isSelected }
        binding.confirmButton.isEnabled = isEnabled
    }

    private fun handleError(message: String) {
        showError(message)
        binding.retryButton.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel coroutines first to prevent new operations
        coroutineScope.cancel()

        // Clean up PhotoManager
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                photoManager.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }
}