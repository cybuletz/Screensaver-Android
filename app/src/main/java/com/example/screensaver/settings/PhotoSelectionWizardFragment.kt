package com.example.screensaver.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.RequestManager
import com.example.screensaver.R
import com.example.screensaver.databinding.FragmentPhotoSelectionWizardBinding
import com.example.screensaver.photos.PhotoGridAdapter
import com.example.screensaver.shared.GooglePhotosManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.screensaver.localphotos.LocalPhotoSelectionActivity
import com.example.screensaver.AlbumSelectionActivity
import com.example.screensaver.models.MediaItem
import com.example.screensaver.PhotoRepository
import com.google.android.material.snackbar.Snackbar
import com.example.screensaver.photos.PhotoManagerViewModel



@AndroidEntryPoint
class PhotoSelectionWizardFragment : Fragment(), WizardStep {
    private var _binding: FragmentPhotoSelectionWizardBinding? = null
    private val binding get() = _binding!!

    private val sourceSelectionState: SourceSelectionState by activityViewModels()
    private val photoSelectionState: PhotoSelectionState by activityViewModels()

    @Inject
    lateinit var glide: RequestManager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var photoRepository: PhotoRepository

    private val photoAdapter by lazy {
        PhotoGridAdapter(
            glide = glide,
            onPhotoClick = { photo -> togglePhotoSelection(photo.id) }
        )
    }

    private val pickMultipleMedia = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(
            100
        )
    ) { uris ->
        if (uris.isNotEmpty()) {
            lifecycleScope.launch {
                try {
                    val mediaItems = uris.map { uri ->
                        MediaItem(
                            id = uri.toString(),
                            albumId = "google_photos",
                            baseUrl = uri.toString(),
                            mimeType = "image/*",
                            width = 0,
                            height = 0,
                            createdAt = System.currentTimeMillis(),
                            loadState = MediaItem.LoadState.IDLE
                        )
                    }

                    // Add to repository
                    photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)

                    // Update selection state
                    photoSelectionState.addPhotos(uris.map { it.toString() })
                    updateSelectedCount()
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing photo selection", e)
                    showError(getString(R.string.photo_selection_failed))
                }
            }
        }
    }

    companion object {
        private const val TAG = "PhotoSelectionState"
        private const val REQUEST_GOOGLE_PHOTOS = AlbumSelectionActivity.REQUEST_PICKER
        private const val REQUEST_LOCAL_PHOTOS = 1001
        private const val REQUEST_LOCAL_ALBUMS = 1002
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoSelectionWizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSourceButtons()
        observeSelections()
    }

    private fun setupRecyclerView() {
        binding.photoGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = photoAdapter
        }
    }

    private fun setupSourceButtons() {
        val selectedSources = sourceSelectionState.selectedSources.value

        binding.apply {
            // Local source buttons (both photos and albums)
            localPhotosButton.visibility =
                if (selectedSources.contains("local")) View.VISIBLE else View.GONE
            localAlbumsButton.visibility =
                if (selectedSources.contains("local")) View.VISIBLE else View.GONE

            // Google Photos button
            googlePhotosButton.visibility =
                if (selectedSources.contains("google_photos")) View.VISIBLE else View.GONE

            // Set click listeners
            localPhotosButton.setOnClickListener { launchLocalPhotosPicker() }
            localAlbumsButton.setOnClickListener { launchLocalAlbumsPicker() }
            googlePhotosButton.setOnClickListener { launchGooglePicker() }
        }
    }

    override fun getTitle(): String = getString(R.string.select_photos)

    override fun getDescription(): String = getString(R.string.photo_selection_description)

    private fun togglePhotoSelection(photoId: String) {
        photoSelectionState.togglePhotoSelection(photoId)
    }

    private fun launchLocalPhotosPicker() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                // Use modern picker for individual photos
                val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(intent, REQUEST_LOCAL_PHOTOS)
            } else {
                // Legacy picker for older devices
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(
                    Intent.createChooser(intent, getString(R.string.select_photos)),
                    REQUEST_LOCAL_PHOTOS
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching local photos picker", e)
            // Fallback to basic picker
            val intent = Intent(Intent.ACTION_PICK).apply {
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, REQUEST_LOCAL_PHOTOS)
        }
    }

    private fun launchLocalAlbumsPicker() {
        // Use your existing AlbumSelectionActivity for album selection
        val intent = Intent(requireContext(), AlbumSelectionActivity::class.java).apply {
            putExtra(AlbumSelectionActivity.EXTRA_PHOTO_SOURCE, AlbumSelectionActivity.SOURCE_LOCAL_PHOTOS)
            val selectedPhotos = photoSelectionState.selectedPhotos.value
                .filter { it.startsWith("content://") }
            putExtra("selected_photos", ArrayList(selectedPhotos))
        }
        startActivityForResult(intent, REQUEST_LOCAL_ALBUMS)
    }

    private fun launchGooglePicker() {
        try {
            pickMultipleMedia.launch(PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error launching photo picker", e)
            showError(getString(R.string.photo_picker_launch_failed))
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            REQUEST_LOCAL_ALBUMS -> {
                data.getStringArrayListExtra("selected_photos")?.let { selectedPhotos ->
                    lifecycleScope.launch {
                        // Add to PhotoSelectionState
                        photoSelectionState.addPhotos(selectedPhotos)
                        updateSelectedCount()
                        Log.d(TAG, "Added ${selectedPhotos.size} photos from local albums")

                        // Important: Move to quick album step if we have photos
                        if (photoSelectionState.isValid()) {
                            (requireActivity() as SetupWizardActivity).handlePhotoSelectionNext()
                        }
                    }
                }
            }
            REQUEST_LOCAL_PHOTOS -> {
                val selectedUris = mutableListOf<Uri>()

                try {
                    // Handle multiple selection
                    data.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i).uri?.let { uri ->
                                // Take persistent permissions first
                                requireContext().contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                                selectedUris.add(uri)
                            }
                        }
                    } ?: data.data?.let { uri -> // Handle single selection
                        // Take persistent permission for single selection
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        selectedUris.add(uri)
                    }

                    if (selectedUris.isNotEmpty()) {
                        lifecycleScope.launch {
                            val mediaItems = selectedUris.map { uri ->
                                MediaItem(
                                    id = uri.toString(),
                                    albumId = "local_picked",
                                    baseUrl = uri.toString(),
                                    mimeType = "image/*",
                                    width = 0,
                                    height = 0,
                                    createdAt = System.currentTimeMillis(),
                                    loadState = MediaItem.LoadState.IDLE
                                )
                            }
                            photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)
                            photoSelectionState.addPhotos(selectedUris.map { it.toString() })
                            updateSelectedCount()
                            Log.d(TAG, "Added ${selectedUris.size} local photos")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to take persistent permissions", e)
                    showError(getString(R.string.photo_permission_error))
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing selected photos", e)
                    showError(getString(R.string.photo_selection_failed))
                }
            }
        }
    }

    private fun updateSelectedCount() {
        val count = photoSelectionState.selectedPhotos.value.size
        binding.selectedCountText.text = getString(R.string.photos_selected_count, count)
        // The wizard will automatically check isValid() when needed
        // No need to explicitly trigger validation
    }

    override fun isValid(): Boolean {
        return photoSelectionState.selectedPhotos.value.isNotEmpty().also { valid ->
            Log.d(TAG, "Checking if step is valid: $valid (${photoSelectionState.selectedPhotos.value.size} photos selected)")
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun observeSelections() {
        viewLifecycleOwner.lifecycleScope.launch {
            photoSelectionState.selectedPhotos.collect { selectedPhotos ->
                val managedPhotos = photoRepository.getAllPhotos()
                    .filter { photo -> photo.baseUrl in selectedPhotos }
                    .map { photo ->
                        PhotoManagerViewModel.ManagedPhoto(
                            id = photo.baseUrl,
                            uri = photo.baseUrl,
                            sourceType = when {
                                photo.baseUrl.contains("com.google.android.apps.photos") ->
                                    PhotoManagerViewModel.PhotoSourceType.GOOGLE_PHOTOS
                                photo.baseUrl.startsWith("content://media/") ->
                                    PhotoManagerViewModel.PhotoSourceType.LOCAL_PICKED
                                else -> PhotoManagerViewModel.PhotoSourceType.VIRTUAL_ALBUM
                            },
                            albumId = photo.albumId,
                            dateAdded = photo.createdAt,
                            isSelected = true
                        )
                    }
                photoAdapter.submitList(managedPhotos)
                updateSelectedCount()
            }
        }
    }
}