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
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import android.content.Intent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.lock.PhotoLockScreenService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import kotlinx.coroutines.launch


@AndroidEntryPoint
class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlbumSelectionBinding
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var dreamServiceHelper: DreamServiceHelper
    private lateinit var glideRequestManager: RequestManager
    private val loadingJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + loadingJob)

    @Inject
    lateinit var photoManager: GooglePhotosManager

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    @Inject
    lateinit var lockScreenPhotoManager: LockScreenPhotoManager

    @Inject
    lateinit var preferences: AppPreferences

    private val viewModel: AlbumSelectionViewModel by viewModels()



    companion object {
        private const val TAG = "AlbumSelectionActivity"
        private const val PRECACHE_COUNT = 5
        const val EXTRA_PHOTO_SOURCE = "photo_source"
        const val SOURCE_GOOGLE_PHOTOS = "google_photos"
        const val SOURCE_LOCAL_PHOTOS = "local_photos"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Glide RequestManager
        glideRequestManager = Glide.with(this)

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
        albumAdapter = AlbumAdapter(
            glideRequestManager = glideRequestManager,  // Add glideRequestManager here
            onAlbumClick = { album ->
                if (!viewModel.isLoading.value) {
                    toggleAlbumSelection(album)
                }
            }
        )

        binding.albumRecyclerView.apply {
            layoutManager = GridLayoutManager(this@AlbumSelectionActivity, 2)
            adapter = albumAdapter
            setItemViewCacheSize(20)
            setHasFixedSize(true)
        }
    }

    private fun setupConfirmButton() {
        binding.confirmButton.apply {
            isEnabled = false
            setOnClickListener {
                if (!viewModel.isLoading.value) {
                    saveSelectedAlbums()
                    // Remove setResult and finish from here
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

        // Add this block
        lifecycleScope.launch {
            preferences.selectedAlbumsFlow.collectLatest { selectedAlbumIds ->
                val currentList = albumAdapter.currentList.map { album ->
                    album.copy(isSelected = selectedAlbumIds.contains(album.id))
                }
                albumAdapter.submitList(currentList)
                updateConfirmButtonState()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            Glide.get(applicationContext).clearMemory()
            binding.albumRecyclerView.recycledViewPool.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop", e)
        }
    }

    private fun initializeGooglePhotos() {
        // Change from coroutineScope to activityScope
        activityScope.launch {
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

    private fun handleError(message: String) {
        showToast(message)
        binding.retryButton.visibility = View.VISIBLE
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(
                this@AlbumSelectionActivity,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private suspend fun loadAlbums() {
        try {
            viewModel.setLoading(true)

            withContext(Dispatchers.IO) {
                withTimeout(10000) {
                    val startTime = System.currentTimeMillis()
                    val albums = photoManager.getAlbums()
                    val selectedAlbumIds = preferences.getSelectedAlbumIds()

                    val albumModels = albums.map { googleAlbum ->
                        Album(
                            id = googleAlbum.id,
                            title = googleAlbum.title,
                            coverPhotoUrl = googleAlbum.coverPhotoUrl.orEmpty(),
                            mediaItemsCount = googleAlbum.mediaItemsCount.toInt(),
                            isSelected = selectedAlbumIds.contains(googleAlbum.id)
                        ).also { album ->
                            // Only preload if coverPhotoUrl is not empty
                            googleAlbum.coverPhotoUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                                photoLoadingManager.preloadPhoto(
                                    MediaItem(
                                        id = album.id,
                                        albumId = "album_covers",
                                        baseUrl = url,
                                        mimeType = "image/jpeg",
                                        width = 512,
                                        height = 512
                                    )
                                )
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (albumModels.isEmpty()) {
                            handleError(getString(R.string.no_albums_found))
                        } else {
                            albumAdapter.submitList(albumModels)
                        }
                    }

                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Total album loading time: ${duration}ms")
                }
            }
        } catch (e: TimeoutCancellationException) {
            handleError(getString(R.string.albums_load_timeout))
        } catch (e: Exception) {
            handleError(getString(R.string.albums_load_error, e.message))
        } finally {
            viewModel.setLoading(false)
        }
    }

    private fun toggleAlbumSelection(album: Album) {
        // First check the actual state in preferences
        val isCurrentlySelected = preferences.getSelectedAlbumIds().contains(album.id)
        // Toggle based on the actual state from preferences
        val shouldBeSelected = !isCurrentlySelected

        Log.d(TAG, "Toggling album ${album.title}: current state in prefs=$isCurrentlySelected, UI state=${album.isSelected}")

        // Update preferences first
        if (shouldBeSelected) {
            preferences.addSelectedAlbumId(album.id)
        } else {
            // Only check if this would leave us with no albums selected
            val currentSelected = preferences.getSelectedAlbumIds()
            if (currentSelected.size == 1 && currentSelected.contains(album.id)) {
                showToast(getString(R.string.keep_one_album))
                return
            }
            preferences.removeSelectedAlbumId(album.id)
        }

        // Then update UI to match preferences
        val updatedAlbum = album.copy(isSelected = shouldBeSelected)
        val currentList = albumAdapter.currentList.toMutableList()
        val position = currentList.indexOfFirst { it.id == album.id }

        if (position != -1) {
            currentList[position] = updatedAlbum
            albumAdapter.submitList(currentList) {
                // Update confirm button state after the list update is complete
                updateConfirmButtonState()
            }
            showSelectionToast(updatedAlbum)
        }

        Log.d(TAG, "After toggle: album ${album.title} selected=$shouldBeSelected")
    }

    private fun showSelectionToast(album: Album) {
        val stringResId = if (album.isSelected) R.string.album_added else R.string.album_removed
        Toast.makeText(
            this,
            getString(stringResId, album.title),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateLoadingText(text: String) {
        binding.loadingText.text = text
    }

    private fun updateLoadingState(isLoading: Boolean) {
        binding.apply {
            loadingContainer.visibility = if (isLoading) View.VISIBLE else View.GONE
            albumRecyclerView.visibility = if (isLoading) View.GONE else View.VISIBLE
            confirmButton.isEnabled = !isLoading && albumAdapter.currentList.any { it.isSelected }
        }
    }

    private fun saveSelectedAlbums() {
        lifecycleScope.launch {
            try {
                viewModel.setLoading(true)
                updateLoadingText("Saving selected albums...")

                // 1. First save the selections
                val selectedAlbums = albumAdapter.currentList
                    .filter { it.isSelected }
                    .map { it.id }
                    .toSet()

                // 2. Save selected album IDs
                preferences.setSelectedAlbumIds(selectedAlbums)

                // 3. Load photos for selected albums
                updateLoadingText("Loading photos...")
                val photos = withContext(Dispatchers.IO) {
                    try {
                        photoManager.loadPhotos()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading photos", e)
                        null
                    }
                }

                if (photos == null) {
                    showToast("No photos found in selected albums")
                    return@launch
                }

                val photoList = photos.toList()
                if (photoList.isEmpty()) {
                    showToast("No photos found in selected albums")
                    return@launch
                }

                // 4. Save photos to LockScreenPhotoManager
                updateLoadingText("Saving photos...")
                withContext(Dispatchers.IO) {
                    lockScreenPhotoManager.clearPhotos()
                    lockScreenPhotoManager.addPhotos(photoList)
                }

                // 5. Clean up resources
                updateLoadingText("Cleaning up...")
                withContext(Dispatchers.IO) {
                    try {
                        Glide.get(applicationContext).clearMemory()
                        albumAdapter.clearAllHolders()
                        photoManager.cleanup()
                        delay(500) // Wait for cleanup
                    } catch (e: Exception) {
                        Log.e(TAG, "Cleanup error", e)
                    }
                }

                // 6. Return to MainActivity
                val mainIntent = Intent(this@AlbumSelectionActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("photos_ready", true)
                    putExtra("photo_count", photoList.size)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                startActivity(mainIntent)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Error during album selection save", e)
                showToast(getString(R.string.save_error))
            } finally {
                viewModel.setLoading(false)
            }
        }
    }

    private suspend fun precachePhotos() {
        try {
            val photoCount = photoManager.getPhotoCount()
            val countToPreload = minOf(PRECACHE_COUNT, photoCount)
            Log.d(TAG, "Starting to precache $countToPreload photos out of total $photoCount")

            if (countToPreload > 0) {
                for (index in 0 until countToPreload) {
                    try {
                        val url = photoManager.getPhotoUrl(index)
                        if (url != null) {
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
                            Log.d(TAG, "Successfully precached photo $index with URL: $url")
                        } else {
                            Log.e(TAG, "Failed to get URL for photo $index")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error precaching photo $index", e)
                    }
                }
            } else {
                Log.d(TAG, "No photos to precache")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during precaching", e)
            throw e
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

    private fun updateConfirmButtonState() {
        val selectedCount = preferences.getSelectedAlbumIds().size
        val isEnabled = !viewModel.isLoading.value && selectedCount > 0

        binding.confirmButton.apply {
            this.isEnabled = isEnabled
            text = if (isEnabled) {
                getString(R.string.confirm_selection)
            } else {
                getString(R.string.select_at_least_one)
            }
        }

        Log.d(TAG, "Updated confirm button state: enabled=$isEnabled, selectedCount=$selectedCount")
    }

    private fun showError(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(
                this@AlbumSelectionActivity,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        // Clear all image loads first
        glideRequestManager.clear(binding.albumRecyclerView)
        binding.albumRecyclerView.adapter = null

        lifecycleScope.launch {
            try {
                withContext(NonCancellable) {
                    photoManager.cleanup()
                    photoLoadingManager.clearMemory()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            } finally {
                loadingJob.cancel()
                activityScope.cancel()
            }
        }
        super.onDestroy()
    }

}