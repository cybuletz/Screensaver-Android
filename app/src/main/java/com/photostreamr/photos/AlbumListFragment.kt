package com.photostreamr.photos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.photostreamr.R
import com.photostreamr.databinding.FragmentAlbumListBinding
import com.google.android.material.snackbar.Snackbar
import com.photostreamr.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import coil.ImageLoader

@AndroidEntryPoint
class AlbumListFragment : Fragment() {
    private var _binding: FragmentAlbumListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoManagerViewModel by activityViewModels()

    @Inject
    lateinit var imageLoader: ImageLoader

    private lateinit var virtualAlbumsAdapter: VirtualAlbumsAdapter

    companion object {
        private const val TAG = "AlbumListFragment"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        Log.d(TAG, "onCreateView called")
        _binding = FragmentAlbumListBinding.inflate(inflater, container, false)

        // Verify button exists in layout
        if (binding.confirmSelectionButton == null) {
            Log.e(TAG, "confirmSelectionButton is null in binding!")
        } else {
            Log.d(TAG, "confirmSelectionButton found in binding")
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated called")
        setupVirtualAlbumsRecyclerView()
        setupConfirmButton()
        observeSelections()
    }

    private fun setupConfirmButton() {
        Log.d(TAG, "Setting up confirm button")
        binding.confirmSelectionButton.apply {
            Log.d(TAG, "Initial button state - enabled: $isEnabled")
            setOnClickListener {
                Log.d(TAG, "Confirm button clicked")
                viewModel.saveVirtualAlbums()
                Snackbar.make(
                    binding.root,
                    getString(R.string.albums_selection_saved),
                    Snackbar.LENGTH_SHORT
                ).show()

                // After saving virtual albums, navigate to MainActivity directly
                val mainIntent = Intent(requireActivity(), MainActivity::class.java).apply {
                    // The following flags are critical to clear the back stack and start MainActivity fresh
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP

                    // Add this extra flag to indicate we're coming from album selection
                    putExtra("from_album_selection", true)
                }
                startActivity(mainIntent)
                requireActivity().finish() // Important - finish the current activity
            }
        }
    }

    private fun observeSelections() {
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d(TAG, "Starting selection observation")
            try {
                viewModel.virtualAlbums.collect { albums ->
                    val hasSelections = albums.any { it.isSelected }
                    Log.d(TAG, "Albums received: ${albums.size}, hasSelections: $hasSelections")
                    binding.confirmSelectionButton.apply {
                        isEnabled = hasSelections
                        if (hasSelections) {
                            alpha = 1f
                            setBackgroundColor(resources.getColor(R.color.blue_light, null))
                        } else {
                            alpha = 0.5f
                            setBackgroundColor(resources.getColor(R.color.gray, null))
                        }
                        Log.d(TAG, "Button state updated - enabled: $isEnabled, hasSelections: $hasSelections")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in observeSelections: ${e.message}")
            }
        }
    }

    private fun setupVirtualAlbumsRecyclerView() {
        virtualAlbumsAdapter = VirtualAlbumsAdapter(
            imageLoader = imageLoader,
            onAlbumClick = { album: VirtualAlbum ->
                Log.d(TAG, "Album clicked: ${album.id}")
            },
            onAlbumOptionsClick = { album: VirtualAlbum ->
                (activity as? PhotoManagerActivity)?.showVirtualAlbumOptions(album)
            },
            onAlbumSelectionChanged = { album: VirtualAlbum, isSelected: Boolean ->
                viewModel.toggleVirtualAlbumSelection(album.id)
                Log.d(TAG, "Album selection changed: ${album.id}, selected: $isSelected")
                // Force check button state
                val currentAlbums = viewModel.virtualAlbums.value
                val hasSelections = currentAlbums.any { it.isSelected }
                Log.d(TAG, "Current selection state after toggle: hasSelections=$hasSelections")
            }
        )

        binding.recyclerViewVirtualAlbums.apply {
            adapter = virtualAlbumsAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.virtualAlbums.collectLatest { albums ->
                Log.d(TAG, "Received ${albums.size} albums, selected count: ${albums.count { it.isSelected }}")
                virtualAlbumsAdapter.submitList(albums)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}