package com.example.screensaver.photomanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.screensaver.R
import com.example.screensaver.databinding.FragmentCollectionListBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CollectionListFragment : Fragment() {
    private var _binding: FragmentCollectionListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CollectionListViewModel by viewModels()
    private lateinit var adapter: CollectionListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = CollectionListAdapter(
            onCollectionClick = { collection ->
                // Will be handled in Step 4
                viewModel.selectCollection(collection)
            },
            onDeleteClick = { collection ->
                showDeleteConfirmation(collection)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CollectionListFragment.adapter
        }
    }

    private fun setupFab() {
        binding.addCollectionFab.setOnClickListener {
            showCreateCollectionDialog()
        }
    }

    private fun showCreateCollectionDialog() {
        val dialogBinding = layoutInflater.inflate(R.layout.dialog_create_collection, null)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_collection)
            .setView(dialogBinding)
            .setPositiveButton(R.string.create) { dialog, _ ->
                val nameInput = dialogBinding.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.collectionNameInput)
                val descInput = dialogBinding.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.collectionDescriptionInput)

                viewModel.createCollection(
                    name = nameInput.text?.toString() ?: "",
                    description = descInput.text?.toString()
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmation(collection: PhotoCollectionManager.Collection) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_collection)
            .setMessage(getString(R.string.delete_collection_confirmation, collection.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteCollection(collection)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.collections.collectLatest { collections ->
                adapter.submitList(collections)
                updateEmptyState(collections.isEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collectLatest { event ->
                when (event) {
                    is CollectionListViewModel.Event.ShowError -> {
                        Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
                    }
                    is CollectionListViewModel.Event.CollectionCreated -> {
                        Snackbar.make(binding.root,
                            getString(R.string.collection_created, event.collection.name),
                            Snackbar.LENGTH_SHORT).show()
                    }
                    is CollectionListViewModel.Event.CollectionDeleted -> {
                        Snackbar.make(binding.root,
                            getString(R.string.collection_deleted, event.collection.name),
                            Snackbar.LENGTH_SHORT)
                            .setAction(R.string.undo) {
                                viewModel.undoDeleteCollection(event.collection)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}