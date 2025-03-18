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
import kotlinx.coroutines.*
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.provider.MediaStore
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.screensaver.PhotoRepository.PhotoAddMode
import com.example.screensaver.photos.PhotoManagerActivity
import com.example.screensaver.photos.PhotoManagerViewModel
import com.example.screensaver.photos.PhotoUriManager
import java.io.IOException


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

    @Inject
    lateinit var photoUriManager: PhotoUriManager


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

    private suspend fun createMediaItemFromUri(uri: Uri, uriData: PhotoUriManager.UriData): MediaItem {
        return withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT
            )

            try {
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    cursor.moveToFirst()

                    val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val mimeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                    val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)

                    // Create MediaItem with all available metadata
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
                } ?: createFallbackMediaItem(uri, uriData)
            } catch (e: Exception) {
                Log.w(TAG, "Error querying media details, creating fallback item", e)
                createFallbackMediaItem(uri, uriData)
            }
        }
    }

    private fun createFallbackMediaItem(uri: Uri, uriData: PhotoUriManager.UriData): MediaItem {
        return MediaItem(
            id = uri.toString(),
            albumId = "picked_photos",
            baseUrl = uri.toString(),
            mimeType = "image/*",
            width = 0,
            height = 0,
            description = uri.lastPathSegment,
            createdAt = uriData.timestamp,
            loadState = MediaItem.LoadState.IDLE
        )
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
                    val processedUris = mutableListOf<Uri>()

                    // Part 1: Extract URIs from the result intent
                    when {
                        // Handle multiple selection via clipData
                        data?.clipData != null -> {
                            val clipData = data.clipData!!
                            for (i in 0 until clipData.itemCount) {
                                clipData.getItemAt(i).uri?.let { uri ->
                                    processedUris.add(uri)
                                    updateLoadingText(getString(R.string.processing_photo_progress, i + 1, clipData.itemCount))
                                }
                            }
                        }
                        // Handle single selection
                        data?.data != null -> {
                            processedUris.add(data.data!!)
                        }
                        // No data received
                        else -> {
                            throw IOException("No image data received from picker")
                        }
                    }
                    // Part 2: Process each URI with our PhotoUriManager
                    if (processedUris.isNotEmpty()) {
                        updateLoadingText(getString(R.string.securing_permissions))

                        // Process all URIs and get the metadata
                        val uriDataList = photoUriManager.processSelectedUris(processedUris)

                        // For each URI data, create a MediaItem
                        withContext(Dispatchers.IO) {
                            uriDataList.forEachIndexed { index, uriData ->
                                try {
                                    val uri = Uri.parse(uriData.uri)

                                    // Create MediaItem
                                    val mediaItem = createMediaItemFromUri(uri, uriData)
                                    selectedMediaItems.add(mediaItem)

                                    // Update loading UI
                                    withContext(Dispatchers.Main) {
                                        updateLoadingText(getString(
                                            R.string.processing_photo_progress,
                                            index + 1,
                                            uriDataList.size
                                        ))
                                    }

                                    // Log permission status
                                    Log.d(TAG, "Processed URI: $uri, Persisted: ${uriData.hasPersistedPermission}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing URI: ${uriData.uri}", e)
                                }
                            }
                        }
                        // Part 3: Save the processed media items
                        if (selectedMediaItems.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                photoManager.cleanup() // Clean up old state

                                // Add the new MediaItems with their metadata
                                photoRepository.addPhotos(
                                    photos = selectedMediaItems,
                                    mode = PhotoRepository.PhotoAddMode.APPEND
                                )

                                // Clear album selections since we're using picked photos
                                preferences.clearSelectedAlbums()

                                // Save the URIs to preferences as well for backup
                                val uriStrings = selectedMediaItems.map { it.baseUrl }.toSet()
                                preferences.updateLocalSelectedPhotos(uriStrings)
                            }

                            // Return to MainActivity with status
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
                    } else {
                        handleError(getString(R.string.no_photos_selected))
                    }
                } catch (e: Exception) {
                    handleError(getString(R.string.photo_processing_error, e.message))
                    Log.e(TAG, "Error processing photos", e)
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
        try {
            // Get the appropriate photo picker intent from PhotoUriManager
            val photoPickerIntent = photoUriManager.getPhotoPickerIntent(true)

            // For Android 13+ (API 33+), we can launch the picker directly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startActivityForResult(photoPickerIntent, PICK_IMAGES_REQUEST_CODE)
                return
            }

            // For Android 11-12, try to use Google Photos directly first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val googlePhotosIntent = photoUriManager.getGooglePhotosIntent(true)
                    startActivityForResult(googlePhotosIntent, PICK_IMAGES_REQUEST_CODE)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch Google Photos directly, falling back to chooser", e)
                }
            }

            // For all other cases, use a chooser
            val chooserIntent = Intent.createChooser(photoPickerIntent, getString(R.string.select_pictures))
            startActivityForResult(chooserIntent, PICK_IMAGES_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching photo picker", e)
            handleError(getString(R.string.photo_picker_error))
        }
    }

    private suspend fun processUri(uri: Uri, mediaItems: MutableList<MediaItem>, index: Int = 0, total: Int = 1) {
        try {
            Log.d(TAG, "Processing URI: $uri")

            // First try to take persistable permission
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // If persistable permission fails, try to at least keep the temporary permission
                Log.w(TAG, "Could not take persistable permission, using temporary permission")
            }

            // Create MediaItem even if we only have temporary permission
            val mediaItem = MediaItem(
                id = uri.toString(),
                albumId = "picked_photos",
                baseUrl = uri.toString(),
                mimeType = contentResolver.getType(uri) ?: "image/*",
                width = 0,
                height = 0,
                description = null,
                createdAt = System.currentTimeMillis(),
                loadState = MediaItem.LoadState.IDLE
            )

            mediaItems.add(mediaItem)

            if (total > 1) {
                updateLoadingText(getString(R.string.processing_photo_progress, index + 1, total))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing URI: $uri", e)
            throw e
        }
    }

    private fun startStandardPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

            // Try to force Google Photos by setting the package
            `package` = "com.google.android.apps.photos"

            // Try to set initial URI to Google Photos
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.google.android.apps.photos.contentprovider"))
        }

        try {
            startActivityForResult(intent, PICK_IMAGES_REQUEST_CODE)
        } catch (e: Exception) {
            Log.d(TAG, "Direct launch failed, using chooser: ${e.message}")

            // If direct launch fails, fall back to chooser with Google Photos as priority
            val chooserIntent = Intent.createChooser(intent, getString(R.string.select_pictures))
            startActivityForResult(chooserIntent, PICK_IMAGES_REQUEST_CODE)
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
                    val albumPhotos = photoRepository.getPhotosFromAlbum(albumId)
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
            photoRepository.addPhotos(
                photos = photos,
                mode = PhotoAddMode.APPEND
            )
        }
    }

    private suspend fun saveGooglePhotos() {
        Log.d(TAG, "Starting saveGooglePhotos()")
        updateLoadingText("Loading photos from Google Photos...")

        val photos = withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Loading photos from photoManager")
                photoManager.loadPhotos()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading Google Photos", e)
                null
            }
        }

        if (photos == null || photos.isEmpty()) {
            Log.e(TAG, "No photos loaded")
            showToast(getString(R.string.no_photos_found))
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        try {
            withContext(Dispatchers.IO) {
                photoRepository.addPhotos(
                    photos = photos,
                    mode = PhotoAddMode.APPEND
                )
            }
            Log.d(TAG, "Photos saved successfully")
            setResult(Activity.RESULT_OK)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving photos", e)
            setResult(Activity.RESULT_CANCELED)
        } finally {
            finish()
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

                // 5. Return to calling activity
                if (intent.getStringExtra("parent_activity") == "com.example.screensaver.photos.PhotoManagerActivity") {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    // Existing main activity navigation code
                    val mainIntent = Intent(this@AlbumSelectionActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("albums_saved", true)
                        putExtra("photo_count", photoRepository.getPhotoCount())
                        putExtra("timestamp", System.currentTimeMillis())
                    }
                    startActivity(mainIntent)
                    finish()
                }

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

    override fun finish() {
        // Get the parent activity from the intent
        val parentActivity = intent.getStringExtra("parent_activity")
        if (parentActivity == "com.example.screensaver.photos.PhotoManagerActivity") {
            // Return to PhotoManagerActivity
            navigateUpTo(Intent(this, PhotoManagerActivity::class.java))
        } else {
            // Default behavior for SettingsActivity
            super.finish()
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