package com.example.screensaver

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.screensaver.adapters.AlbumAdapter
import com.example.screensaver.databinding.ActivityAlbumSelectionBinding
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.PhotoLoadingManager
import com.example.screensaver.utils.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import android.content.Intent
import com.example.screensaver.lock.LockScreenPhotoManager
import kotlinx.coroutines.*
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import android.net.Uri
import com.example.screensaver.lock.LockScreenPhotoManager.PhotoAddMode
import com.example.screensaver.photos.PhotoManagerViewModel


@AndroidEntryPoint
class AlbumSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlbumSelectionBinding
    private lateinit var albumAdapter: AlbumAdapter
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
    private var photoSource = SOURCE_GOOGLE_PHOTOS
    private val photoManagerViewModel: PhotoManagerViewModel by viewModels()



    companion object {
        private const val TAG = "AlbumSelectionActivity"
        private const val PRECACHE_COUNT = 5
        const val EXTRA_PHOTO_SOURCE = "photo_source"
        const val SOURCE_GOOGLE_PHOTOS = "google_photos"
        const val SOURCE_LOCAL_PHOTOS = "local_photos"
        private const val PICK_IMAGES_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get photo source from intent
        photoSource = intent.getStringExtra(EXTRA_PHOTO_SOURCE) ?: SOURCE_GOOGLE_PHOTOS

        // Initialize Glide RequestManager with optimized settings
        glideRequestManager = Glide.with(this)
            .setDefaultRequestOptions(
                RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache both original & resized images
                    .skipMemoryCache(false) // Use memory cache
                    .centerCrop() // Consistent cropping
                    .dontAnimate() // Prevent animation issues
                    .timeout(10000) // 10 second timeout
                    .placeholder(R.drawable.placeholder_album)
                    .error(R.drawable.placeholder_album_error)
            )

        setupViews()
        observeViewModel()

        // Initialize based on source
        if (photoSource == SOURCE_GOOGLE_PHOTOS) {
            initializeGooglePhotos()
        } else {
            initializeLocalPhotos()
        }
    }

    private fun setupViews() {
        setupRecyclerView()
        setupConfirmButton()
        setupRetryButton()
    }

    private fun initializeLocalPhotos() {
        activityScope.launch {
            try {
                viewModel.setLoading(true)
                loadLocalAlbums()
            } catch (e: Exception) {
                handleError(getString(R.string.albums_load_error, e.message))
            } finally {
                viewModel.setLoading(false)
            }
        }
    }

    private suspend fun loadLocalAlbums() {
        try {
            viewModel.setLoading(true)

            withContext(Dispatchers.IO) {
                withTimeout(10000) {
                    val startTime = System.currentTimeMillis()
                    val albums = lockScreenPhotoManager.getLocalAlbums()
                    val selectedAlbumIds = preferences.getSelectedAlbumIds()

                    val albumModels = albums.map { localAlbum ->
                        Album(
                            id = localAlbum.id.toString(),
                            title = localAlbum.name,
                            coverPhotoUrl = localAlbum.coverPhotoUri.toString(),
                            mediaItemsCount = localAlbum.photosCount,
                            isSelected = selectedAlbumIds.contains(localAlbum.id.toString())
                        )
                    }

                    withContext(Dispatchers.Main) {
                        if (albumModels.isEmpty()) {
                            handleError(getString(R.string.no_albums_found))
                        } else {
                            albumAdapter.submitList(albumModels)
                        }
                    }

                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Total local album loading time: ${duration}ms")
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

    private fun setupRecyclerView() {
        binding.albumRecyclerView.apply {
            setItemViewCacheSize(30)
            recycledViewPool.setMaxRecycledViews(0, 30)

            layoutManager = GridLayoutManager(this@AlbumSelectionActivity, 2).apply {
                recycleChildrenOnDetach = true
            }

            albumAdapter = AlbumAdapter(glideRequestManager) { album ->
                if (!viewModel.isLoading.value) {
                    toggleAlbumSelection(album)
                }
            }

            adapter = albumAdapter

            // Add scroll state listener
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            Log.d(TAG, "Scroll IDLE - resuming image loading")
                            glideRequestManager.resumeRequests()
                        }
                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            Log.d(TAG, "Scroll DRAGGING")
                        }
                        RecyclerView.SCROLL_STATE_SETTLING -> {
                            Log.d(TAG, "Scroll SETTLING")
                        }
                    }
                }
            })

            // Observe virtual albums
            lifecycleScope.launch {
                photoManagerViewModel.virtualAlbums.collect { virtualAlbums ->
                    val currentList = albumAdapter.currentList.toMutableList()
                    val selectedAlbumIds = preferences.getSelectedAlbumIds()

                    if (virtualAlbums.isNotEmpty()) {
                        currentList.add(Album(
                            id = "virtual_header",
                            title = "Virtual Albums",
                            coverPhotoUrl = "",
                            mediaItemsCount = 0,
                            isSelected = false,
                            isHeader = true
                        ))

                        currentList.addAll(virtualAlbums.map { virtualAlbum ->
                            Album(
                                id = virtualAlbum.id,
                                title = virtualAlbum.name,
                                coverPhotoUrl = virtualAlbum.photoUris.firstOrNull() ?: "",
                                mediaItemsCount = virtualAlbum.photoUris.size,
                                isSelected = selectedAlbumIds.contains(virtualAlbum.id)
                            )
                        })
                    }

                    albumAdapter.submitList(currentList)
                }
            }
        }
    }

    private fun setupConfirmButton() {
        binding.confirmButton.apply {
            isEnabled = false
            extend() // Ensure it starts extended
            setOnClickListener {
                if (!viewModel.isLoading.value) {
                    saveSelectedAlbums()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.confirmButton.extend()
    }

    override fun onRestart() {
        super.onRestart()
        binding.confirmButton.extend()
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
        Log.d(TAG, "Activity onStop - clearing RecyclerView")
        try {
            // Don't clear memory cache, just pending requests
            glideRequestManager.pauseRequests()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop", e)
        }
    }

    private suspend fun createMediaItemFromUri(uri: Uri): MediaItem {
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )

            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                cursor.moveToFirst()

                val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val mimeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                MediaItem(
                    id = uri.toString(),
                    albumId = "picked_photos",
                    baseUrl = uri.toString(),
                    mimeType = cursor.getString(mimeColumn) ?: "image/*",
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    description = cursor.getString(nameColumn),
                    createdAt = cursor.getLong(dateColumn) * 1000,
                    loadState = MediaItem.LoadState.IDLE
                )
            } ?: MediaItem(
                id = uri.toString(),
                albumId = "picked_photos",
                baseUrl = uri.toString(),
                mimeType = "image/*",
                width = 0,
                height = 0,
                description = uri.lastPathSegment,
                createdAt = System.currentTimeMillis(),
                loadState = MediaItem.LoadState.IDLE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGES_REQUEST_CODE && resultCode == RESULT_OK) {
            lifecycleScope.launch {
                try {
                    viewModel.setLoading(true)
                    updateLoadingText(getString(R.string.processing_photos))

                    val selectedMediaItems = mutableListOf<MediaItem>()

                    // Handle multiple selection
                    data?.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i).uri?.let { uri ->
                                try {
                                    contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                    // Convert URI to MediaItem with metadata
                                    val mediaItem = createMediaItemFromUri(uri)
                                    selectedMediaItems.add(mediaItem)

                                    // Update loading progress
                                    updateLoadingText(getString(R.string.processing_photo_progress,
                                        i + 1,
                                        clipData.itemCount))
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing photo at index $i", e)
                                }
                            }
                        }
                    } ?: data?.data?.let { uri -> // Handle single selection
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            // Convert single URI to MediaItem
                            val mediaItem = createMediaItemFromUri(uri)
                            selectedMediaItems.add(mediaItem)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing single photo", e)
                        }
                    }

                    if (selectedMediaItems.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            photoManager.cleanup() // Clean up old state
                            // Add the new MediaItems with their metadata
                            lockScreenPhotoManager.addPhotos(
                                photos = selectedMediaItems,
                                mode = PhotoAddMode.APPEND  // Explicitly use APPEND mode
                            )
                            // Clear album selections since we're using picked photos
                            preferences.clearSelectedAlbums()
                        }

                        // Return to MainActivity
                        val mainIntent = Intent(this@AlbumSelectionActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("albums_saved", true)
                            putExtra("photo_count", selectedMediaItems.size)
                            putExtra("timestamp", System.currentTimeMillis())
                            putExtra("force_reload", true)
                        }
                        startActivity(mainIntent)
                        finish()
                    } else {
                        handleError(getString(R.string.no_photos_selected))
                    }
                } catch (e: Exception) {
                    handleError(getString(R.string.photo_processing_error, e.message))
                } finally {
                    viewModel.setLoading(false)
                }
            }
        }
    }

    private fun startPhotoPicker() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100) // Adjust max as needed
                }
                startActivityForResult(intent, PICK_IMAGES_REQUEST_CODE)
            } else {
                // If device doesn't support the photo picker, fall back to Google Photos API
                initializeGooglePhotos()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching photo picker: ${e.message}")
            // Fall back to Google Photos API
            initializeGooglePhotos()
        }
    }

    private fun initializeGooglePhotos() {
        // First try the system photo picker on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            startPhotoPicker()
            return
        }

        // Otherwise use existing Google Photos implementation
        activityScope.launch {
            try {
                viewModel.setLoading(true)
                if (photoManager.initialize()) {
                    loadAlbums()
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
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@AlbumSelectionActivity, message, Toast.LENGTH_SHORT).show()
            binding.retryButton.visibility = View.VISIBLE
        }
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
        if (album.isHeader) return

        val isCurrentlySelected = preferences.getSelectedAlbumIds().contains(album.id)
        val shouldBeSelected = !isCurrentlySelected

        if (shouldBeSelected) {
            preferences.addSelectedAlbumId(album.id)
        } else {
            val currentSelected = preferences.getSelectedAlbumIds()
            if (currentSelected.size == 1 && currentSelected.contains(album.id)) {
                showToast(getString(R.string.keep_one_album))
                return
            }
            preferences.removeSelectedAlbumId(album.id)
        }

        val updatedAlbum = album.copy(isSelected = shouldBeSelected)
        val currentList = albumAdapter.currentList.toMutableList()
        val position = currentList.indexOfFirst { it.id == album.id }

        if (position != -1) {
            currentList[position] = updatedAlbum
            albumAdapter.submitList(currentList) {
                updateConfirmButtonState()
            }
            showSelectionToast(updatedAlbum)
        }

        // Handle virtual album selection
        if (album.id.startsWith("virtual_")) {
            photoManagerViewModel.toggleVirtualAlbumSelection(album.id)
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

    private suspend fun saveLocalPhotos(selectedAlbumIds: Set<String>) {
        updateLoadingText("Loading photos from local albums...")
        val photos = withContext(Dispatchers.IO) {
            val allPhotos = mutableListOf<MediaItem>()
            selectedAlbumIds.forEach { albumId ->
                try {
                    Log.d(TAG, "Loading photos from album: $albumId")
                    val albumPhotos = lockScreenPhotoManager.getPhotosFromAlbum(albumId)
                    allPhotos.addAll(albumPhotos)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security permission error for album $albumId", e)
                }
            }
            Log.d(TAG, "Loaded ${allPhotos.size} photos from local albums")
            allPhotos
        }

        if (photos.isEmpty()) {
            showToast(getString(R.string.no_photos_found))
            return
        }

        updateLoadingText("Saving photos...")
        withContext(Dispatchers.IO) {
            lockScreenPhotoManager.addPhotos(
                photos = photos,
                mode = PhotoAddMode.APPEND
            )
        }
    }

    private suspend fun saveGooglePhotos() {
        updateLoadingText("Loading photos from Google Photos...")
        val photos = withContext(Dispatchers.IO) {
            try {
                photoManager.loadPhotos()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Google Photos", e)
                null
            }
        }

        if (photos == null || photos.isEmpty()) {
            showToast(getString(R.string.no_photos_found))
            return
        }

        updateLoadingText("Saving photos...")
        withContext(Dispatchers.IO) {
            lockScreenPhotoManager.addPhotos(
                photos = photos.toList(),
                mode = PhotoAddMode.APPEND  // Use APPEND to ensure we don't lose existing photos
            )
        }
    }

    private fun saveSelectedAlbums() {
        lifecycleScope.launch {
            try {
                viewModel.setLoading(true)
                updateLoadingText("Saving selected albums...")

                // 1. Get currently selected albums from this view
                val newSelectedAlbums = albumAdapter.currentList
                    .filter { it.isSelected }
                    .map { it.id }
                    .toSet()

                // 2. Get existing selected albums and merge with new selections
                val existingSelectedAlbums = preferences.getSelectedAlbumIds()
                val mergedSelections = existingSelectedAlbums + newSelectedAlbums

                // 3. Save merged album IDs
                preferences.setSelectedAlbumIds(mergedSelections)

                // 4. Load and save photos based on source
                if (photoSource == SOURCE_LOCAL_PHOTOS) {
                    saveLocalPhotos(mergedSelections)
                } else {
                    saveGooglePhotos()
                }

                // 5. Return to MainActivity but don't start slideshow
                val mainIntent = Intent(this@AlbumSelectionActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("albums_saved", true)
                    putExtra("photo_count", lockScreenPhotoManager.getPhotoCount())
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
        Log.d(TAG, "Activity onDestroy")
        // Clear all image loads first
        glideRequestManager.clear(binding.albumRecyclerView)
        binding.albumRecyclerView.adapter = null

        lifecycleScope.launch {
            try {
                withContext(NonCancellable) {
                    photoManager.cleanup()
                    // Only clear memory if the activity is actually being destroyed
                    if (isFinishing) {
                        Log.d(TAG, "Clearing Glide memory cache")
                        Glide.get(applicationContext).clearMemory()
                    }
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