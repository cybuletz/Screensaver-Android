package com.example.screensaver

import android.app.Activity
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
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.provider.MediaStore
import android.net.Uri
import com.example.screensaver.PhotoRepository.PhotoAddMode
import com.example.screensaver.photos.PhotoManagerViewModel
import com.example.screensaver.settings.PhotoSelectionState


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
    lateinit var photoRepository: PhotoRepository

    @Inject
    lateinit var preferences: AppPreferences

    private val viewModel: AlbumSelectionViewModel by viewModels()
    private var photoSource = SOURCE_GOOGLE_PHOTOS
    private val photoManagerViewModel: PhotoManagerViewModel by viewModels()
    private val photoSelectionState: PhotoSelectionState by viewModels()


    companion object {
        private const val TAG = "AlbumSelectionActivity"
        private const val PRECACHE_COUNT = 5
        const val EXTRA_PHOTO_SOURCE = "photo_source"
        const val SOURCE_GOOGLE_PHOTOS = "google_photos"
        const val SOURCE_LOCAL_PHOTOS = "local_photos"
        const val REQUEST_PICKER = 100
        private const val PERMISSION_REQUEST_CODE = 100
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
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .skipMemoryCache(false)
                    .centerCrop()
                    .dontAnimate()
                    .timeout(10000)
                    .placeholder(R.drawable.placeholder_album)
                    .error(R.drawable.placeholder_album_error)
            )

        setupViews()
        observeViewModel()

        // Initialize based on source
        if (photoSource == SOURCE_GOOGLE_PHOTOS) {
            initializeGooglePhotos()
        } else {
            checkAndRequestPermissions() // Call here instead of initializeLocalPhotos()
        }
    }

    private fun checkAndRequestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES), PERMISSION_REQUEST_CODE)
                return
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
                return
            }
        }
        initializeLocalPhotos()  // Only initialize after permissions are granted
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeLocalPhotos()
            } else {
                handleError(getString(R.string.permission_denied))
            }
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
                Log.d(TAG, "Starting to load local albums")
                loadLocalAlbums()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading local albums", e)
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
                    val albums = photoRepository.getLocalAlbums()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        if (resultCode != Activity.RESULT_OK) {
            Log.d(TAG, "Result not OK")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        when (requestCode) {
            REQUEST_PICKER -> {
                Log.d(TAG, "Handling photo picker result")
                val uris = mutableListOf<Uri>()

                try {
                    // Check for multiple selection
                    data?.clipData?.let { clipData ->
                        Log.d(TAG, "Multiple selection: ${clipData.itemCount} items")
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i).uri?.let { uri ->
                                uris.add(uri)
                            }
                        }
                    } ?: run {
                        // Single selection
                        data?.data?.let { uri ->
                            Log.d(TAG, "Single selection: $uri")
                            uris.add(uri)
                        }
                    }

                    if (uris.isNotEmpty()) {
                        Log.d(TAG, "Processing ${uris.size} selected photos")
                        lifecycleScope.launch {
                            try {
                                // Create MediaItems first
                                val mediaItems = uris.map { uri ->
                                    MediaItem(
                                        id = uri.toString(),
                                        albumId = if (photoSource == SOURCE_GOOGLE_PHOTOS) "google_photos" else "local_photos",
                                        baseUrl = uri.toString(),
                                        mimeType = "image/*",
                                        width = 0,
                                        height = 0,
                                        createdAt = System.currentTimeMillis()
                                    )
                                }

                                // Add to repository
                                photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)

                                // Create result intent with selected URIs
                                val resultIntent = Intent().apply {
                                    putStringArrayListExtra("selected_photos", ArrayList(uris.map { it.toString() }))
                                    putExtra("source", photoSource)
                                }

                                setResult(Activity.RESULT_OK, resultIntent)
                                finish()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing photos", e)
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            }
                        }
                    } else {
                        Log.e(TAG, "No URIs found in picker result")
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling picker result", e)
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        }
    }

    private fun startPhotoPicker() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100)
                    // Add a flag to identify this is part of the wizard flow
                    putExtra("is_wizard_flow", true)
                    putExtra("source", photoSource) // Pass the source to maintain context
                }
                startActivityForResult(intent, REQUEST_PICKER)
            } else if (photoSource == SOURCE_LOCAL_PHOTOS) {
                // For local photos on older devices
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra("is_wizard_flow", true)
                    putExtra("source", photoSource)
                }
                startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.select_photos)),
                    REQUEST_PICKER
                )
            } else {
                // For Google Photos, use existing flow
                initializeGooglePhotos()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching photo picker: ${e.message}")
            if (photoSource == SOURCE_GOOGLE_PHOTOS) {
                initializeGooglePhotos()
            } else {
                // Fallback for local photos
                val intent = Intent(Intent.ACTION_PICK).apply {
                    type = "image/*"
                    putExtra("is_wizard_flow", true)
                    putExtra("source", photoSource)
                }
                startActivityForResult(intent, REQUEST_PICKER)
            }
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

    private fun saveSelectedAlbums() {
        lifecycleScope.launch {
            try {
                viewModel.setLoading(true)
                updateLoadingText("Saving selected albums...")

                // 1. Get currently selected albums
                val selectedAlbums = albumAdapter.currentList
                    .filter { it.isSelected }
                    .map { it.id }
                    .toSet()

                // 2. Load photos from selected albums
                val selectedPhotos = mutableListOf<String>()
                if (photoSource == SOURCE_LOCAL_PHOTOS) {
                    selectedAlbums.forEach { albumId ->
                        val photos = photoRepository.getPhotosFromAlbum(albumId)
                        selectedPhotos.addAll(photos.map { it.baseUrl })
                    }
                }

                // 3. Save to PhotoRepository
                val mediaItems = selectedPhotos.map { uri ->
                    MediaItem(
                        id = uri,
                        albumId = "local_album",
                        baseUrl = uri,
                        mimeType = "image/*",
                        width = 0,
                        height = 0,
                        createdAt = System.currentTimeMillis(),
                        loadState = MediaItem.LoadState.IDLE
                    )
                }
                photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)

                // 4. Save album IDs to preferences
                preferences.setSelectedAlbumIds(selectedAlbums)

                // 5. Set result with selected photos
                val resultIntent = Intent().apply {
                    putStringArrayListExtra("selected_photos", ArrayList(selectedPhotos))
                    // Add flags to identify this is within the wizard flow
                    putExtra("is_wizard_flow", true)
                    putExtra("source", photoSource)
                }

                setResult(Activity.RESULT_OK, resultIntent)
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