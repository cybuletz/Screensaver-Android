package com.photostreamr.photos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.databinding.FragmentPhotoListBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import coil.ImageLoader

@AndroidEntryPoint
class PhotoListFragment : Fragment() {
    private var _binding: FragmentPhotoListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoManagerViewModel by activityViewModels()

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var photoUriManager: PhotoUriManager

    private val photoAdapter by lazy {
        PhotoGridAdapter(
            imageLoader = imageLoader,
            photoUriManager = photoUriManager,
            onPhotoClick = { photo ->
                // Use our safe method
                (activity as? PhotoManagerActivity)?.togglePhotoSelectionSafely(photo.id)
            },
            onPhotoLoadError = { photo, error ->
                // Handle load errors (optional)
                viewModel.markPhotoLoadError(photo.id, error)
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeToDelete()
        observePhotos()
    }

    private fun setupRecyclerView() {
        binding.photoRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = photoAdapter
        }
    }

    private fun setupSwipeToDelete() {
        val itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                0, // Drag directions - we don't want drag
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT // Swipe directions
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean = false // We don't support moving items

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val position = viewHolder.bindingAdapterPosition
                    val photo = photoAdapter.currentList[position]

                    (activity as? PhotoManagerActivity)?.run {
                        togglePhotoSelectionSafely(photo.id)
                        showDeleteConfirmationDialogSafely()
                    }
                }
            }
        )
        itemTouchHelper.attachToRecyclerView(binding.photoRecyclerView)
    }

    private fun observePhotos() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photos.collectLatest { photos ->
                // Don't refresh the list if we're just updating selection state
                if (photos.size != photoAdapter.currentList.size ||
                    photos.any { newPhoto ->
                        !photoAdapter.currentList.any { it.id == newPhoto.id }
                    }) {
                    photoAdapter.submitList(photos)
                } else {
                    // Just update the items that changed their selection state
                    photoAdapter.notifyItemRangeChanged(0, photos.size)
                }
                binding.photoRecyclerView.isVisible = photos.isNotEmpty()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}