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
import com.example.screensaver.models.MediaItem
import com.example.screensaver.photos.VirtualAlbum
import com.example.screensaver.photos.PhotoManagerViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import com.example.screensaver.photos.PhotoGridAdapter

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

    private fun createVirtualAlbum(name: String) {  // Changed from createAlbum
        lifecycleScope.launch {
            try {
                photoManagerViewModel.createVirtualAlbum(
                    name = name,
                    isSelected = true
                )
                showMessage(getString(R.string.album_created))
            } catch (e: Exception) {
                showMessage(getString(R.string.album_creation_error))
            }
        }
    }

    private fun createDefaultAlbum() {
        lifecycleScope.launch {
            try {
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

    private fun observePhotos() {
        viewLifecycleOwner.lifecycleScope.launch {
            photoManagerViewModel.photos.collect { photos ->
                if (photos.isNotEmpty()) {
                    // Update photo count
                    binding.photoCount.text = getString(R.string.photos_selected_count, photos.size)

                    // Enable buttons when we have photos
                    binding.createAlbumButton.isEnabled = true
                    binding.skipButton.isEnabled = true

                    // Convert first 6 photos to display format
                    val previewPhotos = photos.take(6)
                    photoAdapter.submitList(previewPhotos)
                } else {
                    binding.photoCount.text = getString(R.string.no_photos_selected)
                    binding.createAlbumButton.isEnabled = false
                    binding.skipButton.isEnabled = false
                    photoAdapter.submitList(emptyList())
                }
            }
        }


        // Also observe PhotoManagerViewModel's state
        viewLifecycleOwner.lifecycleScope.launch {
            photoManagerViewModel.state.collect { state ->
                when (state) {
                    is PhotoManagerViewModel.PhotoManagerState.Loading -> {
                        binding.createAlbumButton.isEnabled = false
                        binding.skipButton.isEnabled = false
                    }
                    is PhotoManagerViewModel.PhotoManagerState.Empty -> {
                        binding.photoCount.text = getString(R.string.no_photos_selected)
                        binding.createAlbumButton.isEnabled = false
                        binding.skipButton.isEnabled = false
                        photoAdapter.submitList(emptyList())
                    }
                    is PhotoManagerViewModel.PhotoManagerState.Success -> {
                        // State will be handled by photos observer
                    }
                    is PhotoManagerViewModel.PhotoManagerState.Error -> {
                        showMessage(state.message)
                    }
                    is PhotoManagerViewModel.PhotoManagerState.Idle -> {
                        // State will be handled by photos observer
                    }
                }
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