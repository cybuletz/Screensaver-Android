package com.example.screensaver.photos

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import android.widget.EditText
import android.text.InputFilter
import android.text.InputType
import com.google.android.material.snackbar.Snackbar

@AndroidEntryPoint
class PhotoManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPhotoManagerBinding
    private val viewModel: PhotoManagerViewModel by viewModels()
    private val PERMISSION_REQUEST_CODE = 123

    @Inject
    lateinit var glide: RequestManager

    companion object {
        private const val TAG = "PhotoManagerActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        binding = ActivityPhotoManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupObservers()
        setupActions()
        setupViewPager()
        setupFab()
        observeState()
    }

    private fun setupViewPager() {
        val pagerAdapter = PhotoManagerPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.photos)
                1 -> getString(R.string.albums)
                else -> ""
            }
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Show FAB only in Photos tab (position 0)
                binding.createAlbumFab.isVisible = position == 0
            }
        })
    }

    private fun setupFab() {
        binding.createAlbumFab.setOnClickListener {
            showCreateAlbumDialog()
        }
    }

    private fun showCreateAlbumDialog() {
        val editText = EditText(this).apply {
            hint = "Album Name"
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(30))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Create Virtual Album")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val albumName = editText.text.toString().trim()
                if (albumName.isNotEmpty()) {
                    viewModel.createVirtualAlbum(albumName)
                    // We'll switch to Albums tab after successful creation
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                when (state) {
                    is PhotoManagerState.Loading -> binding.loadingState.root.isVisible = true
                    else -> binding.loadingState.root.isVisible = false
                }

                if (state is PhotoManagerState.Error) {
                    Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.photo_manager_menu, menu)
        menu.findItem(R.id.action_remove_selected)?.let { menuItem ->
            menuItem.icon = AppCompatResources.getDrawable(
                this,
                android.R.drawable.ic_menu_delete
            )
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasSelection = viewModel.selectedCount.value > 0
        menu.findItem(R.id.action_select_all).isVisible = !hasSelection
        menu.findItem(R.id.action_deselect_all).isVisible = hasSelection
        menu.findItem(R.id.action_remove_selected).isVisible = hasSelection
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_remove_selected -> {
                showDeleteConfirmationDialog()
                true
            }
            R.id.sort_date_newest -> {
                viewModel.sortPhotos(SortOption.DATE_DESC)
                true
            }
            R.id.sort_date_oldest -> {
                viewModel.sortPhotos(SortOption.DATE_ASC)
                true
            }
            R.id.sort_by_source -> {
                viewModel.groupPhotosBySource()
                true
            }
            R.id.action_select_all -> {
                viewModel.selectAllPhotos()
                invalidateOptionsMenu()
                true
            }
            R.id.action_deselect_all -> {
                viewModel.deselectAllPhotos()
                invalidateOptionsMenu()
                true
            }
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                when (state) {
                    PhotoManagerState.Loading -> {
                        binding.loadingState.root.isVisible = true
                    }
                    is PhotoManagerState.Success -> {
                        binding.loadingState.root.isVisible = false
                        showMessage(state.message)
                        // If album was created successfully, switch to Albums tab
                        if (state.message == "Album created successfully") {
                            binding.viewPager.currentItem = 1
                        }
                    }
                    is PhotoManagerState.Error -> {
                        binding.loadingState.root.isVisible = false
                        showError(state.message)
                    }
                    PhotoManagerState.Idle -> {
                        binding.loadingState.root.isVisible = false
                    }
                    PhotoManagerState.Empty -> {
                        binding.loadingState.root.isVisible = false
                        showMessage(getString(R.string.no_photos_found))
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.selectedCount.collectLatest { count ->
                updateSelectionUI(count)
                invalidateOptionsMenu()
            }
        }

        lifecycleScope.launch {
            viewModel.virtualAlbums.collectLatest { albums ->
                Log.d(TAG, "Received ${albums.size} virtual albums")
                // Update UI or notify fragments if needed
            }
        }
    }

    private fun updateSelectionUI(count: Int) {
        binding.toolbar.subtitle = if (count > 0) {
            resources.getQuantityString(R.plurals.photos_selected, count, count)
        } else {
            null
        }
        binding.createAlbumFab.isEnabled = count > 0
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.manage_photos)
    }

    override fun onPause() {
        super.onPause()
        viewModel.saveVirtualAlbums()
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadState()
        viewModel.reloadVirtualAlbums()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES), PERMISSION_REQUEST_CODE)
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.reloadState()
            } else {
                Toast.makeText(this, "Permission required to access photos", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupActions() {
        binding.createAlbumFab.setOnClickListener {
            showCreateAlbumDialog()
        }
    }

    fun showVirtualAlbumOptions(album: PhotoManagerViewModel.VirtualAlbum) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Album Options")
            .setItems(arrayOf("Delete Album")) { _, which ->
                when (which) {
                    0 -> viewModel.deleteVirtualAlbum(album.id)
                }
            }
            .show()
    }

    fun showDeleteConfirmationDialog() {
        val selectedCount = viewModel.selectedCount.value
        if (selectedCount == 0) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete)
            .setMessage(resources.getQuantityString(
                R.plurals.confirm_delete_photos,
                selectedCount,
                selectedCount
            ))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.removeSelectedPhotos()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}