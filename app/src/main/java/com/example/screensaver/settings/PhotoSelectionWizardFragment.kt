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
        private const val REQUEST_LOCAL_PHOTOS = 1001
        private const val REQUEST_GOOGLE_PHOTOS = 1002
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

    override fun isValid(): Boolean {
        return photoAdapter.currentList.any { it.isSelected }
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
        if (resultCode != Activity.RESULT_OK) return

        when (requestCode) {
            REQUEST_LOCAL_PHOTOS -> handleLocalPhotosResult(data)
            REQUEST_GOOGLE_PHOTOS -> handleGooglePhotosResult(data)
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
            val photos = googlePhotosManager.loadPhotos()
            photos?.let { mediaItems ->
                photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)
                updatePhotoSelection(mediaItems.map { it.baseUrl })
            }
        }
    }

    private fun updatePhotoSelection(newPhotos: List<String>) {
        photoSelectionState.addPhotos(newPhotos)
        updateSelectedCount()
    }


    private fun updateSelectedCount() {
        binding.selectedCountText.text = getString(
            R.string.photos_selected_count,
            photoSelectionState.selectedPhotos.value.size
        )
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