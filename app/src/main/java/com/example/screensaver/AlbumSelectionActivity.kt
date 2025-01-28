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

@AndroidEntryPoint
class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlbumSelectionBinding
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var dreamServiceHelper: DreamServiceHelper
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

    private suspend fun loadAlbums() {
        try {
            viewModel.setLoading(true)
            val albums = photoManager.getAlbums()
            // Get selected albums before mapping to ensure consistency
            val selectedAlbumIds = preferences.getSelectedAlbumIds()

            Log.d(TAG, "Loading albums. Selected albums: ${selectedAlbumIds.size}")

            withContext(Dispatchers.Main) {
                val albumModels = albums.map { googleAlbum ->
                    val isSelected = selectedAlbumIds.contains(googleAlbum.id)
                    Log.d(TAG, "Album ${googleAlbum.title} selection state: $isSelected")

                    Album(
                        id = googleAlbum.id,
                        title = googleAlbum.title,
                        coverPhotoUrl = googleAlbum.coverPhotoUrl ?: "",
                        mediaItemsCount = googleAlbum.mediaItemsCount.toInt(),
                        isSelected = isSelected
                    )
                }

                if (albumModels.isEmpty()) {
                    handleError(getString(R.string.no_albums_found))
                } else {
                    Log.d(TAG, "Loaded ${albumModels.size} albums")
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
        // First check the actual state in preferences
        val isCurrentlySelected = preferences.getSelectedAlbumIds().contains(album.id)
        // Toggle based on the actual state from preferences
        val shouldBeSelected = !isCurrentlySelected

        Log.d(TAG, "Toggling album ${album.title}: current state in prefs=$isCurrentlySelected, UI state=${album.isSelected}")

        // Update preferences first
        if (shouldBeSelected) {
            preferences.addSelectedAlbumId(album.id)
        } else {
            preferences.removeSelectedAlbumId(album.id)
        }

        // Then update UI to match preferences
        val updatedAlbum = album.copy(isSelected = shouldBeSelected)
        val currentList = albumAdapter.currentList.toMutableList()
        val position = currentList.indexOfFirst { it.id == album.id }

        if (position != -1) {
            currentList[position] = updatedAlbum
            albumAdapter.submitList(currentList)
            updateConfirmButtonState()
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

    private fun saveSelectedAlbums() {
        val selectedAlbums = albumAdapter.currentList
            .filter { it.isSelected }
            .map { it.id }
            .toSet()

        Log.d(TAG, "Saving selected albums: $selectedAlbums")
        preferences.setSelectedAlbumIds(selectedAlbums)

        lifecycleScope.launch {
            try {
                val photos = photoManager.loadPhotos()
                if (photos != null) {
                    // Clear existing photos first
                    lockScreenPhotoManager.clearPhotos()
                    lockScreenPhotoManager.addPhotos(photos)

                    // Start precaching
                    withContext(Dispatchers.IO) {
                        precachePhotos()
                    }

                    // Update service
                    val serviceIntent = Intent(this@AlbumSelectionActivity, PhotoLockScreenService::class.java).apply {
                        action = "AUTH_UPDATED"
                    }
                    startService(serviceIntent)

                    // Update main activity
                    val mainIntent = Intent(this@AlbumSelectionActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("photos_ready", true)
                    }
                    startActivity(mainIntent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                withContext(Dispatchers.Main) {
                    showError(getString(R.string.photos_load_error, e.message))
                }
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
        lifecycleScope.launch {
            try {
                withContext(NonCancellable) { // Change to withContext instead of plus operator
                    photoManager.cleanup()
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