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
import android.util.Log
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

    companion object {
        private const val TAG = "PhotoSelectionState"
        private const val REQUEST_GOOGLE_PHOTOS = AlbumSelectionActivity.REQUEST_PICKER
        private const val REQUEST_LOCAL_PHOTOS = 1001
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
            localPhotosButton.visibility =
                if (selectedSources.contains("local")) View.VISIBLE else View.GONE

            googlePhotosButton.visibility =
                if (selectedSources.contains("google_photos")) View.VISIBLE else View.GONE

            localPhotosButton.setOnClickListener { launchLocalPicker() }
            googlePhotosButton.setOnClickListener { launchGooglePicker() }
        }
    }

    override fun getTitle(): String = getString(R.string.select_photos)
    override fun getDescription(): String = getString(R.string.photo_selection_description)

    private fun togglePhotoSelection(photoId: String) {
        photoSelectionState.togglePhotoSelection(photoId)
    }

    private fun launchLocalPicker() {
        val intent = Intent(requireContext(), LocalPhotoSelectionActivity::class.java).apply {
            val selectedPhotos = photoSelectionState.selectedPhotos.value
                .filter { it.startsWith("content://") }
            putExtra("selected_photos", ArrayList(selectedPhotos))
        }
        startActivityForResult(intent, REQUEST_LOCAL_PHOTOS)
    }

    private fun launchGooglePicker() {
        lifecycleScope.launch {
            try {
                if (googlePhotosManager.initialize()) {
                    startActivityForResult(
                        Intent(requireContext(), AlbumSelectionActivity::class.java),
                        REQUEST_GOOGLE_PHOTOS
                    )
                } else {
                    showError(getString(R.string.google_photos_init_failed))
                }
            } catch (e: Exception) {
                showError(getString(R.string.google_photos_init_failed))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_GOOGLE_PHOTOS -> {
                data?.getStringArrayListExtra("selected_photos")?.let { selectedPhotos ->
                    Log.d(TAG, "Received ${selectedPhotos.size} photos from picker")
                    photoSelectionState.addPhotos(selectedPhotos)
                    updateSelectedCount()
                }
            }
        }
    }

    private fun handleLocalPhotosResult(data: Intent?) {
        data?.getStringArrayListExtra("selected_photos")?.let { selectedPhotos ->
            lifecycleScope.launch {
                val mediaItems = selectedPhotos.map { uri ->
                    MediaItem(
                        id = uri,
                        albumId = "local_picked",
                        baseUrl = uri,
                        mimeType = "image/*",
                        width = 0,
                        height = 0,
                        description = null,
                        createdAt = System.currentTimeMillis(),
                        loadState = MediaItem.LoadState.IDLE
                    )
                }
                photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)
                updatePhotoSelection(selectedPhotos)
            }
        }
    }

    private fun handleGooglePhotosResult(data: Intent?) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Handling Google Photos result")

                // Get the URIs from the picker result
                val clipData = data?.clipData
                val selectedUris = mutableListOf<String>()

                if (clipData != null) {
                    // Multiple selections
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uri ->
                            selectedUris.add(uri.toString())
                        }
                    }
                } else {
                    // Single selection
                    data?.data?.let { uri ->
                        selectedUris.add(uri.toString())
                    }
                }

                if (selectedUris.isNotEmpty()) {
                    Log.d(TAG, "Selected ${selectedUris.size} photos from Google Photos picker")

                    // Update PhotoSelectionState first
                    photoSelectionState.addPhotos(selectedUris)

                    // Then create and add MediaItems to repository
                    val mediaItems = selectedUris.map { uri ->
                        MediaItem(
                            id = uri,
                            albumId = "google_photos",
                            baseUrl = uri,
                            mimeType = "image/*",
                            width = 0,
                            height = 0,
                            createdAt = System.currentTimeMillis(),
                            loadState = MediaItem.LoadState.IDLE
                        )
                    }
                    photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)

                    // Update UI
                    binding.selectedCountText.text = getString(
                        R.string.photos_selected_count,
                        photoSelectionState.selectedPhotos.value.size
                    )
                } else {
                    Log.e(TAG, "No photos selected from picker")
                    showError(getString(R.string.no_photos_selected))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error handling Google Photos result", e)
                showError(getString(R.string.photo_selection_failed))
            }
        }
    }

    private fun createMediaItem(uri: Uri): MediaItem {
        return MediaItem(
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

    private fun updatePhotoSelection(newPhotos: List<String>) {
        photoSelectionState.addPhotos(newPhotos)
        updateSelectedCount()
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