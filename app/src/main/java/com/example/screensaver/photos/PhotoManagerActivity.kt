package com.example.screensaver.photos

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.RequestManager
import com.example.screensaver.R
import com.example.screensaver.databinding.ActivityPhotoManagerBinding
import com.example.screensaver.databinding.DialogCreateAlbumBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.screensaver.photos.PhotoManagerViewModel.PhotoManagerState
import com.example.screensaver.photos.PhotoManagerViewModel.SortOption

@AndroidEntryPoint
class PhotoManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhotoManagerBinding
    private val viewModel: PhotoManagerViewModel by viewModels()

    @Inject
    lateinit var glide: RequestManager

    private val photoAdapter by lazy {
        PhotoGridAdapter(
            glide = glide,
            onPhotoClick = { photo -> viewModel.togglePhotoSelection(photo.id) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupObservers()
        setupActions()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.manage_photos)
    }

    private fun setupRecyclerView() {
        binding.photoRecyclerView.apply {
            layoutManager = GridLayoutManager(this@PhotoManagerActivity, 3)
            adapter = photoAdapter
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                when (state) {
                    PhotoManagerState.Loading -> showLoading(true)
                    is PhotoManagerState.Error -> {
                        showLoading(false)
                        showError(state.message)
                    }
                    is PhotoManagerState.Success -> {
                        showLoading(false)
                        showMessage(state.message)
                    }
                    PhotoManagerState.Idle -> showLoading(false)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.photos.collectLatest { photos ->
                photoAdapter.submitList(photos)
            }
        }

        lifecycleScope.launch {
            viewModel.selectedCount.collectLatest { count ->
                updateSelectionUI(count)
            }
        }
    }

    private fun setupActions() {
        binding.createAlbumFab.setOnClickListener {
            showCreateAlbumDialog()
        }

        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_sort -> {
                    showSortOptions()
                    true
                }
                else -> false
            }
        }
    }

    private fun showCreateAlbumDialog() {
        if (viewModel.selectedCount.value == 0) {
            showError(getString(R.string.no_photos_selected))
            return
        }

        val dialogBinding = DialogCreateAlbumBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_create_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val name = dialogBinding.albumNameInput.text.toString()
                if (name.isNotBlank()) {
                    viewModel.createVirtualAlbum(name)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showSortOptions() {
        val options = arrayOf(
            getString(R.string.sort_date_newest),
            getString(R.string.sort_date_oldest),
            getString(R.string.sort_by_source)
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort)
            .setItems(options) { _, which ->
                val sortOption = when (which) {
                    0 -> SortOption.DATE_DESC
                    1 -> SortOption.DATE_ASC
                    2 -> SortOption.SOURCE
                    else -> SortOption.DATE_DESC
                }
                viewModel.sortPhotos(sortOption)
            }
            .show()
    }

    private fun showLoading(show: Boolean) {
        binding.loadingState.root.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateSelectionUI(count: Int) {
        binding.toolbar.subtitle = resources.getQuantityString(
            R.plurals.photos_selected,
            count,
            count
        )
        binding.createAlbumFab.isEnabled = count > 0
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "PhotoManagerActivity"
    }
}