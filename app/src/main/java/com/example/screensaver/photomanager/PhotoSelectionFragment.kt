package com.example.screensaver.photomanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.screensaver.R
import com.example.screensaver.databinding.FragmentPhotoSelectionBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PhotoSelectionFragment : Fragment() {
    private var _binding: FragmentPhotoSelectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoSelectionViewModel by viewModels()
    private lateinit var adapter: PhotoSelectionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupToolbar()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = PhotoSelectionAdapter(
            onPhotoClick = { photo, isSelected ->
                viewModel.togglePhotoSelection(photo, isSelected)
            }
        )

        binding.photoGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = this@PhotoSelectionFragment.adapter
        }
    }

    private fun setupToolbar() {
        binding.selectionToolbar.apply {
            inflateMenu(R.menu.menu_photo_selection)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_to_collection -> {
                        showCollectionSelectionDialog()
                        true
                    }
                    R.id.action_clear_selection -> {
                        viewModel.clearSelection()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun showCollectionSelectionDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val collections = viewModel.getAvailableCollections()
            if (collections.isEmpty()) {
                showCreateCollectionPrompt()
                return@launch
            }

            val names = collections.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.add_to_collection)
                .setItems(names) { _, index ->
                    viewModel.addSelectedPhotosToCollection(collections[index])
                }
                .setNeutralButton(R.string.create_new) { _, _ ->
                    showCreateCollectionPrompt()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showCreateCollectionPrompt() {
        val dialogBinding = layoutInflater.inflate(R.layout.dialog_create_collection, null)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_collection)
            .setView(dialogBinding)
            .setPositiveButton(R.string.create) { dialog, _ ->
                val nameInput = dialogBinding.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.collectionNameInput)
                val descInput = dialogBinding.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.collectionDescriptionInput)

                viewModel.createCollectionAndAddPhotos(
                    name = nameInput.text?.toString() ?: "",
                    description = descInput.text?.toString()
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photos.collectLatest { photos ->
                adapter.submitList(photos)
                updateEmptyState(photos.isEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCount.collectLatest { count ->
                updateSelectionUI(count)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collectLatest { event ->
                when (event) {
                    is PhotoSelectionViewModel.Event.ShowError -> {
                        Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                    }
                    is PhotoSelectionViewModel.Event.PhotosAddedToCollection -> {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.photos_added_to_collection, event.count, event.collectionName),
                            Snackbar.LENGTH_SHORT
                        ).show()
                        viewModel.clearSelection()
                    }
                }
            }
        }
    }

    private fun updateSelectionUI(selectedCount: Int) {
        binding.selectionToolbar.visibility = if (selectedCount > 0) View.VISIBLE else View.GONE
        binding.selectionToolbar.title = resources.getQuantityString(
            R.plurals.photos_selected,
            selectedCount,
            selectedCount
        )
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.photoGrid.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}