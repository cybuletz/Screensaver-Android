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
import com.example.screensaver.databinding.DialogCreateAlbumBinding
import com.example.screensaver.databinding.FragmentQuickAlbumBinding
import com.example.screensaver.photos.PhotoManagerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.example.screensaver.photos.PhotoGridAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay


@AndroidEntryPoint
class QuickAlbumFragment : Fragment(), WizardStep {
    private var _binding: FragmentQuickAlbumBinding? = null
    private val binding get() = _binding!!

    private val photoSelectionState: PhotoSelectionState by activityViewModels()
    private val photoManagerViewModel: PhotoManagerViewModel by activityViewModels()

    private val photoAdapter by lazy {
        PhotoGridAdapter(
            glide = glide,
            onPhotoClick = { _ -> } // Empty lambda instead of null
        )
    }

    @Inject
    lateinit var glide: RequestManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQuickAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observePhotos()
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            // First reload state
            photoManagerViewModel.reloadState()

            // Wait for photos to be loaded before selecting
            delay(300) // Give time for the state to update
            selectAllDisplayedPhotos()
        }
    }

    private fun setupViews() {
        binding.apply {
            // Setup RecyclerView
            photoGrid.apply {
                layoutManager = GridLayoutManager(requireContext(), 3)
                adapter = photoAdapter
            }

            createAlbumButton.setOnClickListener {
                showCreateAlbumDialog()
            }

            skipButton.setOnClickListener {
                createDefaultAlbum()
            }

            // Initially disable buttons
            createAlbumButton.isEnabled = false
            skipButton.isEnabled = false
        }
    }

    private fun showCreateAlbumDialog() {
        val dialogBinding = DialogCreateAlbumBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_album)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->  // Use system "OK" instead of custom "create"
                val albumName = dialogBinding.albumNameInput.text?.toString()
                if (!albumName.isNullOrBlank()) {
                    createVirtualAlbum(albumName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)  // Use system "cancel"
            .show()
    }

    private fun observePhotos() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Observe PhotoSelectionState
            launch {
                photoSelectionState.selectedPhotos.collect { selectedPhotos ->
                    if (selectedPhotos.isNotEmpty()) {
                        binding.photoCount.text = getString(R.string.photos_selected_count, selectedPhotos.size)
                        binding.createAlbumButton.isEnabled = true
                        binding.skipButton.isEnabled = true
                    } else {
                        binding.photoCount.text = getString(R.string.no_photos_selected)
                        binding.createAlbumButton.isEnabled = false
                        binding.skipButton.isEnabled = false
                    }
                }
            }

            // Observe PhotoManagerViewModel photos
            launch {
                photoManagerViewModel.photos.collect { photos ->
                    if (photos.isNotEmpty()) {
                        // Convert first 6 photos to display format and update adapter
                        val previewPhotos = photos.take(6)
                        photoAdapter.submitList(previewPhotos)
                    } else {
                        photoAdapter.submitList(emptyList())
                    }
                }
            }

            // Keep existing state observer
            launch {
                photoManagerViewModel.state.collect { state ->
                    when (state) {
                        is PhotoManagerViewModel.PhotoManagerState.Error -> {
                            showMessage(state.message)
                        }
                        is PhotoManagerViewModel.PhotoManagerState.Empty -> {
                            if (photoSelectionState.selectedPhotos.value.isNotEmpty()) {
                                photoManagerViewModel.reloadState()
                            }
                        }
                        else -> { /* Handle other states as needed */ }
                    }
                }
            }
        }
    }

    private fun selectAllDisplayedPhotos() {
        viewLifecycleOwner.lifecycleScope.launch {
            val selectedPhotos = photoSelectionState.selectedPhotos.value
            val currentPhotos = photoManagerViewModel.photos.value

            currentPhotos.forEach { photo ->
                if (selectedPhotos.contains(photo.uri) && !photo.isSelected) {
                    photoManagerViewModel.togglePhotoSelection(photo.id)
                }
            }
        }
    }

    private fun createVirtualAlbum(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val selectedPhotos = photoSelectionState.selectedPhotos.value
                if (selectedPhotos.isEmpty()) {
                    showMessage(getString(R.string.no_photos_selected))
                    return@launch
                }

                // Update PhotoManagerViewModel selections first
                photoManagerViewModel.updatePhotos(
                    selectedPhotos.map { uri ->
                        photoManagerViewModel.photos.value.find { it.uri == uri }
                            ?: PhotoManagerViewModel.ManagedPhoto(
                                id = uri,
                                uri = uri,
                                sourceType = PhotoManagerViewModel.PhotoSourceType.LOCAL_PICKED,
                                albumId = "picked_photos",
                                dateAdded = System.currentTimeMillis()
                            )
                    }
                )

                // Create the album
                photoManagerViewModel.createVirtualAlbum(
                    name = name,
                    isSelected = true
                )

                showMessage(getString(R.string.album_created))
                (requireActivity() as SetupWizardActivity).completeSetup()
            } catch (e: Exception) {
                showMessage(getString(R.string.album_creation_error))
            }
        }
    }

    private fun createDefaultAlbum() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val selectedPhotos = photoSelectionState.selectedPhotos.value
                if (selectedPhotos.isEmpty()) {
                    showMessage(getString(R.string.no_photos_selected))
                    return@launch
                }

                // Update PhotoManagerViewModel selections first
                photoManagerViewModel.updatePhotos(
                    selectedPhotos.map { uri ->
                        photoManagerViewModel.photos.value.find { it.uri == uri }
                            ?: PhotoManagerViewModel.ManagedPhoto(
                                id = uri,
                                uri = uri,
                                sourceType = PhotoManagerViewModel.PhotoSourceType.LOCAL_PICKED,
                                albumId = "picked_photos",
                                dateAdded = System.currentTimeMillis()
                            )
                    }
                )

                // Create the album
                photoManagerViewModel.createVirtualAlbum(
                    name = getString(R.string.default_album_name),
                    isSelected = true
                )

                (requireActivity() as SetupWizardActivity).completeSetup()
            } catch (e: Exception) {
                showMessage(getString(R.string.album_creation_error))
            }
        }
    }

    override fun isValid(): Boolean = true

    override fun getTitle(): String = getString(R.string.organize_photos)

    override fun getDescription(): String = getString(R.string.organize_photos_description)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}