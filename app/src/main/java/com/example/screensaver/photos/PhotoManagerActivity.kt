package com.example.screensaver.photos

import android.content.DialogInterface
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
import android.widget.Button
import androidx.core.view.doOnNextLayout
import com.google.android.material.snackbar.Snackbar
import com.example.screensaver.photos.VirtualAlbum
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.settings.PhotoSourcesPreferencesFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


@AndroidEntryPoint
class PhotoManagerActivity : AppCompatActivity(), PhotoSourcesPreferencesFragment.PhotoSourcesListener {
    private lateinit var binding: ActivityPhotoManagerBinding
    private val viewModel: PhotoManagerViewModel by viewModels()
    private val PERMISSION_REQUEST_CODE = 123

    private val _photos = MutableStateFlow<List<ManagedPhoto>>(emptyList())
    private val photos: StateFlow<List<ManagedPhoto>> = _photos.asStateFlow()
    private val dialogShownThisSession = MutableStateFlow(false)

    @Inject
    lateinit var glide: RequestManager

    @Inject
    lateinit var appPreferences: AppPreferences

    private var isFirstTimeSetup = true
    private lateinit var nextButton: Button
    private var isShowingDialog = false
    private var pendingPhotoCount = 0
    private var hasSkippedThisSession = false

    companion object {
        private const val TAG = "PhotoManagerActivity"
        private const val MENU_DEBUG = Menu.FIRST + 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasSkippedThisSession = false
        binding = ActivityPhotoManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup next button
        nextButton = binding.nextButton.apply {
            visibility = View.VISIBLE
            isEnabled = false
            setOnClickListener {
                showCreateDefaultAlbumDialog()
            }
        }

        isFirstTimeSetup = appPreferences.getBoolean("is_first_time_setup", true)

        setupToolbar()
        setupObservers()
        setupActions()
        setupViewPager()
        setupFab()
        observeState()

        viewModel.reloadState()

        setupPhotoObserver()
    }

    private fun showCreateDefaultAlbumDialog() {
        if (isShowingDialog || dialogShownThisSession.value) {
            Log.d(TAG, "Skip showing dialog - already shown or showing")
            return
        }

        val photoCount = viewModel.photos.value.size
        if (photoCount == 0) {
            Log.d(TAG, "Skip showing dialog - no photos")
            return
        }

        isShowingDialog = true
        dialogShownThisSession.value = true

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_default_album_title)
            .setMessage(getString(R.string.create_default_album_message))
            .setPositiveButton(getString(R.string.create)) { dialog, _ ->
                Log.d(TAG, "Creating default album with $photoCount photos")
                lifecycleScope.launch {
                    try {
                        viewModel.selectAllPhotos()

                        viewModel.createVirtualAlbum(
                            getString(R.string.default_album_name),
                            true
                        )

                        appPreferences.edit {
                            putBoolean("is_first_time_setup", false)
                        }
                        isFirstTimeSetup = false

                        withContext(Dispatchers.Main) {
                            // First enable navigation
                            binding.viewPager.isUserInputEnabled = true

                            // Force layout pass
                            binding.viewPager.requestLayout()
                            binding.viewPager.invalidate()

                            // Navigate with a delay after layout
                            binding.viewPager.post {
                                binding.viewPager.postDelayed({
                                    binding.viewPager.setCurrentItem(2, false)
                                    binding.viewPager.adapter?.notifyDataSetChanged()
                                }, 150)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating album", e)
                        Snackbar.make(binding.root, getString(R.string.album_creation_error), Snackbar.LENGTH_LONG).show()
                    } finally {
                        isShowingDialog = false
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton(getString(R.string.skip)) { dialog, _ ->
                Log.d(TAG, "Skipping default album creation")
                binding.tabLayout.getTabAt(0)?.view?.isClickable = true
                binding.tabLayout.getTabAt(1)?.apply {
                    view?.isClickable = true
                    view?.alpha = 1f
                    select()
                }
                binding.tabLayout.getTabAt(2)?.apply {
                    view?.isClickable = true
                    view?.alpha = 1f
                }
                binding.viewPager.isUserInputEnabled = true
                isShowingDialog = false
                dialog.dismiss()
            }
            .setOnDismissListener {
                isShowingDialog = false
            }
            .setCancelable(false)
            .show()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.selectedCount.collect { count ->
                updateSelectionUI(count)
                if (binding.viewPager.currentItem != 0) {
                    binding.createAlbumFab.visibility = if (count > 0) View.VISIBLE else View.GONE
                }
                invalidateOptionsMenu()
            }
        }
    }

    private fun setupPhotoObserver() {
        lifecycleScope.launch {
            viewModel.photos.collect { photos ->
                val hasPhotos = photos.isNotEmpty()
                Log.d(TAG, "Photos updated: count = ${photos.size}")

                // Update UI state
                updateTabsVisibility(hasPhotos)
                if (binding.viewPager.currentItem == 0) {
                    binding.nextButton.isEnabled = hasPhotos
                }

                // Automatically create default album if we have photos and no dialog shown
                if (hasPhotos && !dialogShownThisSession.value) {
                    createDefaultAlbum()
                    showPhotoAddedDialog()
                }
            }
        }
    }

    private fun createDefaultAlbum() {
        val photoCount = viewModel.photos.value.size
        if (photoCount == 0) return

        lifecycleScope.launch {
            try {
                // Get photos that aren't in any album yet
                val newPhotos = viewModel.getPhotosNotInAlbums()
                if (newPhotos.isEmpty()) {
                    Log.d(TAG, "No new photos to add to album")
                    return@launch
                }

                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date())

                val userName = System.getProperty("user.name") ?: "unknown"
                val albumName = getString(R.string.default_album_name_with_timestamp, timestamp, userName)

                val existingAlbums = viewModel.virtualAlbums.value
                if (existingAlbums.any { it.name.startsWith("Default Album") }) {
                    // Append to existing album
                    viewModel.appendToLatestDefaultAlbum(newPhotos)
                } else {
                    // Create new album
                    viewModel.selectAllPhotos()
                    viewModel.createVirtualAlbum(albumName, true)
                }

                dialogShownThisSession.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error creating default album", e)
            }
        }
    }

    private fun showPhotoAddedDialog() {
        if (isShowingDialog) return
        isShowingDialog = true

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photos_added_title)
            .setMessage(R.string.photos_added_message)
            .setPositiveButton(R.string.add_more_photos) { dialog, _ ->
                // Reset the dialog shown flag so it shows again after next photo selection
                dialogShownThisSession.value = false
                isShowingDialog = false
                dialog.dismiss()
            }
            .setNegativeButton(R.string.go_to_photos) { dialog, _ ->
                lifecycleScope.launch(Dispatchers.Main) {
                    // First dismiss dialog
                    dialog.dismiss()
                    isShowingDialog = false

                    // Reset photo picking session
                    val currentFragment = supportFragmentManager.findFragmentByTag("f0")
                    if (currentFragment is PhotoSourcesPreferencesFragment) {
                        currentFragment.resetPhotoPickingSession()
                    }

                    // Create default album with timestamp
                    createDefaultAlbum()

                    // Navigate to Photos tab
                    binding.viewPager.apply {
                        isUserInputEnabled = false
                        post {
                            setCurrentItem(1, false)
                            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(1))
                            requestLayout()
                            invalidate()
                            postDelayed({ isUserInputEnabled = true }, 100)
                        }
                    }
                }
            }
            .setCancelable(false)
            .show()
    }

    override fun onPhotosAdded(isFirstTime: Boolean) {
        lifecycleScope.launch {
            viewModel.reloadState()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            viewModel.reloadState()
            viewModel.reloadVirtualAlbums()
        }
    }

    override fun onSourceSelectionComplete() {
        // Do nothing - let the dialog handle the navigation
    }

    private fun setupFab() {
        binding.createAlbumFab.setOnClickListener {
            showCreateAlbumDialog()
        }
    }

    private fun showCreateAlbumDialog() {
        val dialogBinding = DialogCreateAlbumBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_album)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_create_title) { _, _ ->
                val albumName = dialogBinding.albumNameInput.text?.toString()?.trim()
                val isSelected = dialogBinding.albumSelectedCheckbox.isChecked // Get checkbox state

                if (!albumName.isNullOrEmpty()) {
                    viewModel.createVirtualAlbum(albumName, isSelected) // Pass checkbox state to ViewModel
                }
            }
            .setNegativeButton(R.string.cancel, null)
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
        // Add debug menu option
        menu.add(0, MENU_DEBUG, 0, "Debug Albums")
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
            MENU_DEBUG -> {
                viewModel.debugPrintAllAlbums()
                true
            }
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    private fun setupViewPager() {
        val hasPhotos = viewModel.photos.value.isNotEmpty()
        val pagerAdapter = PhotoManagerPagerAdapter(this, hasPhotos)
        binding.viewPager.adapter = pagerAdapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = pagerAdapter.getPageTitle(position)
        }.attach()

        // Update adapter when photos change
        lifecycleScope.launch {
            viewModel.photos.collect { photos ->
                val newHasPhotos = photos.isNotEmpty()
                if (newHasPhotos) {
                    // Force recreate adapter with photos enabled
                    binding.viewPager.adapter = PhotoManagerPagerAdapter(this@PhotoManagerActivity, true).also {
                        // Ensure tab layout is properly updated
                        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                            tab.text = it.getPageTitle(position)
                        }.attach()
                    }

                    // Enable all tabs explicitly
                    for (i in 0..2) {
                        binding.tabLayout.getTabAt(i)?.apply {
                            view?.isClickable = true
                            view?.alpha = 1f
                            view?.isEnabled = true
                        }
                    }

                    // Ensure ViewPager can handle navigation
                    binding.viewPager.apply {
                        isUserInputEnabled = true
                        offscreenPageLimit = 2  // Keep all pages in memory
                    }

                    // Force layout update
                    binding.viewPager.requestLayout()
                    binding.tabLayout.requestLayout()
                }
            }
        }

        // Handle tab changes
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonsVisibility(position)
                // Only restrict navigation on Sources tab
                binding.viewPager.isUserInputEnabled = position != 0 || viewModel.photos.value.isNotEmpty()
            }
        })

        // Initial tab setup
        updateTabsVisibility(hasPhotos)
    }

    private fun updateTabsVisibility(enable: Boolean) {
        for (i in 0..2) {
            binding.tabLayout.getTabAt(i)?.apply {
                view?.isClickable = enable || i == 0  // Always enable Sources tab
                view?.alpha = if (enable || i == 0) 1f else 0.5f
                view?.isEnabled = enable || i == 0
            }
        }
        binding.viewPager.isUserInputEnabled = enable
    }

    private fun updateButtonsVisibility(position: Int) {
        val hasPhotos = viewModel.photos.value.isNotEmpty()
        when (position) {
            0 -> { // Sources tab
                binding.nextButton.visibility = View.VISIBLE
                binding.nextButton.isEnabled = hasPhotos
                binding.createAlbumFab.hide()
            }
            else -> { // Photos and Albums tabs
                binding.nextButton.visibility = View.GONE
                binding.createAlbumFab.visibility = if (viewModel.selectedCount.value > 0) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateSelectionUI(count: Int) {
        binding.toolbar.subtitle = if (count > 0) {
            resources.getQuantityString(R.plurals.photos_selected, count, count)
        } else {
            null
        }
    }

    private fun setupActions() {
        binding.createAlbumFab.setOnClickListener {
            showCreateAlbumDialog()
        }
    }

    fun showVirtualAlbumOptions(album: VirtualAlbum) {
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
}