package com.photostreamr.photos

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.RequestManager
import com.photostreamr.R
import com.photostreamr.databinding.ActivityPhotoManagerBinding
import com.photostreamr.databinding.DialogCreateAlbumBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.photostreamr.photos.PhotoManagerViewModel.SortOption
import androidx.appcompat.content.res.AppCompatResources
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import android.widget.ProgressBar
import android.widget.TextView
import com.photostreamr.PhotoRepository
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.photostreamr.MainActivity
import com.photostreamr.utils.AppPreferences
import com.photostreamr.settings.PhotoSourcesPreferencesFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    @Inject
    lateinit var photoRepository: PhotoRepository

    private var isFirstTimeSetup = true
    private var isShowingDialog = false
    private var hasSkippedThisSession = false
    private var isSelectingPhoto = false
    private var tabChangeLocked = false



    companion object {
        private const val TAG = "PhotoManagerActivity"
        private const val MENU_DEBUG = Menu.FIRST + 1
    }

    fun resetDialogShownFlag() {
        dialogShownThisSession.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasSkippedThisSession = false
        binding = ActivityPhotoManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)



        setupToolbar()
        setupObservers()
        setupActions()
        setupViewPager()
        setupFab()
        observeState()
        observeCacheStatus()

        viewModel.reloadState()

        setupPhotoObserver()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.selectedCount.collect { count ->
                updateSelectionUI(count)
                // Update FAB visibility based on selection count and current tab
                updateFabVisibility(count, binding.viewPager.currentItem)
                invalidateOptionsMenu()
            }
        }
    }

    private fun updateFabVisibility(selectedCount: Int, currentTab: Int) {
        // Only show FAB in Photos tab (tab index 1) when photos are selected
        if (currentTab == 1 && selectedCount > 0) {
            binding.createAlbumFab.show()
        } else {
            binding.createAlbumFab.hide()
        }
    }

    private fun setupPhotoObserver() {
        var lastPhotoCount = 0  // Track the previous photo count

        lifecycleScope.launch {
            viewModel.photos.collect { photos ->
                val hasPhotos = photos.isNotEmpty()
                val currentCount = photos.size
                Log.d(TAG, "Photos updated: count = $currentCount")

                // Update UI state
                updateTabsVisibility(hasPhotos)
                if (binding.viewPager.currentItem == 0) {

                    // Only show dialog if we have new photos added in this session
                    if (hasPhotos && !dialogShownThisSession.value && currentCount > lastPhotoCount) {
                        createDefaultAlbum()
                        showPhotoAddedDialog()
                    }
                }

                lastPhotoCount = currentCount
            }
        }
    }

    fun showDeleteConfirmationDialogSafely() {
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
                withLockedTabs {
                    viewModel.removeSelectedPhotos()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

                    // After saving virtual albums, navigate to MainActivity directly
                    val mainIntent = Intent(this@PhotoManagerActivity, MainActivity::class.java).apply {
                        // The following flags are critical to clear the back stack and start MainActivity fresh
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP

                        // Add this extra flag to indicate we're coming from album selection
                        putExtra("from_album_selection", true)
                    }
                    startActivity(mainIntent)
                    finish() // Important - finish the current activity
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
                    is PhotoManagerViewModel.PhotoManagerState.Loading -> binding.loadingState.root.isVisible = true
                    else -> binding.loadingState.root.isVisible = false
                }

                if (state is PhotoManagerViewModel.PhotoManagerState.Error) {
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
                    // Store current position
                    val currentPosition = binding.viewPager.currentItem

                    // Update adapter
                    binding.viewPager.adapter = PhotoManagerPagerAdapter(this@PhotoManagerActivity, true).also {
                        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
                            tab.text = it.getPageTitle(position)
                        }.attach()
                    }

                    // Enable all tabs
                    for (i in 0..2) {
                        binding.tabLayout.getTabAt(i)?.apply {
                            view?.isClickable = true
                            view?.alpha = 1f
                            view?.isEnabled = true
                        }
                    }

                    binding.viewPager.apply {
                        isUserInputEnabled = true
                        offscreenPageLimit = 2

                        // Restore position after a brief delay
                        post {
                            setCurrentItem(currentPosition, false)
                        }
                    }

                    binding.viewPager.requestLayout()
                    binding.tabLayout.requestLayout()
                }
            }
        }

        // Handle tab changes
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // Skip all tab change logic if tabs are locked
                if (tabChangeLocked) {
                    return
                }

                updateButtonsVisibility(position)
                // Only restrict navigation on Sources tab
                binding.viewPager.isUserInputEnabled = position != 0 || viewModel.photos.value.isNotEmpty()
            }
        })

        // Also add a tab selection listener to prevent unwanted tab changes
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                // If tabs are locked, revert to the stored position
                if (tabChangeLocked) {
                    val currentTabPosition = binding.viewPager.currentItem
                    binding.tabLayout.post {
                        binding.tabLayout.getTabAt(currentTabPosition)?.select()
                    }
                    return
                }

                // Otherwise, let the normal selection happen
                if (binding.viewPager.currentItem != tab.position) {
                    binding.viewPager.setCurrentItem(tab.position, false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                // No special handling needed
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                // No special handling needed
            }
        })

        // Initial tab setup
        updateTabsVisibility(hasPhotos)
    }

    private fun withLockedTabs(action: () -> Unit) {
        tabChangeLocked = true
        val currentTabPosition = binding.tabLayout.selectedTabPosition

        try {
            action()
        } finally {
            // Make sure we restore the tab and unlock in a delayed action
            // to catch any pending UI updates
            binding.tabLayout.post {
                binding.tabLayout.getTabAt(currentTabPosition)?.select()
                binding.viewPager.setCurrentItem(currentTabPosition, false)

                // Add a small delay before unlocking to ensure all UI updates are complete
                binding.tabLayout.postDelayed({
                    tabChangeLocked = false
                }, 200) // 200ms delay should be enough to catch most UI updates
            }
        }
    }

    fun togglePhotoSelectionSafely(photoId: String) {
        withLockedTabs {
            viewModel.togglePhotoSelection(photoId)
        }
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

    private fun observeCacheStatus() {
        lifecycleScope.launch {
            try {
                val cacheStatusTextView = findViewById<TextView>(R.id.cache_status_text)
                val cacheProgressBar = findViewById<ProgressBar>(R.id.cache_progress_bar)
                val cacheTotalSizeView = findViewById<TextView>(R.id.cache_total_size)

                if (cacheStatusTextView == null || cacheProgressBar == null) {
                    Log.w(TAG, "Cache status views not found in layout")
                    return@launch
                }

                // Monitor tab changes to show/hide cache status
                binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        super.onPageSelected(position)
                        // Only show cache status on first tab
                        if (position == 0) {
                            // Check current status
                            when (val progress = photoRepository.persistentPhotoCache.cachingProgress.value) {
                                is PersistentPhotoCache.CachingProgress.Idle -> {
                                    cacheStatusTextView.visibility = View.GONE
                                    cacheProgressBar.visibility = View.GONE
                                    cacheTotalSizeView?.visibility = View.VISIBLE
                                }
                                is PersistentPhotoCache.CachingProgress.InProgress,
                                is PersistentPhotoCache.CachingProgress.Starting -> {
                                    cacheStatusTextView.visibility = View.VISIBLE
                                    cacheProgressBar.visibility = View.VISIBLE
                                    cacheTotalSizeView?.visibility = View.VISIBLE
                                }
                                is PersistentPhotoCache.CachingProgress.Complete -> {
                                    // Show for 3 seconds then hide
                                    cacheStatusTextView.visibility = View.VISIBLE
                                    cacheProgressBar.visibility = View.VISIBLE
                                    cacheTotalSizeView?.visibility = View.VISIBLE
                                }
                                is PersistentPhotoCache.CachingProgress.Failed -> {
                                    cacheStatusTextView.visibility = View.VISIBLE
                                    cacheProgressBar.visibility = View.GONE
                                    cacheTotalSizeView?.visibility = View.VISIBLE
                                }
                            }
                        } else {
                            // Hide on other tabs
                            cacheStatusTextView.visibility = View.GONE
                            cacheProgressBar.visibility = View.GONE
                            cacheTotalSizeView?.visibility = View.GONE
                        }
                    }
                })

                // Collect total cache size
                launch {
                    photoRepository.persistentPhotoCache.totalCacheSize.collect { totalSizeBytes ->
                        val formattedSize = photoRepository.persistentPhotoCache.formatFileSize(totalSizeBytes)
                        cacheTotalSizeView?.text = "Total Cache: $formattedSize"
                        // Only show on first tab
                        cacheTotalSizeView?.visibility = if (binding.viewPager.currentItem == 0) View.VISIBLE else View.GONE
                    }
                }

                // Collect caching progress
                photoRepository.persistentPhotoCache.cachingProgress.collect { progress ->
                    // Only apply changes if we're on the first tab
                    if (binding.viewPager.currentItem != 0) {
                        return@collect
                    }

                    when (progress) {
                        is PersistentPhotoCache.CachingProgress.Idle -> {
                            cacheStatusTextView.visibility = View.GONE
                            cacheProgressBar.visibility = View.GONE
                        }
                        is PersistentPhotoCache.CachingProgress.Starting -> {
                            cacheStatusTextView.visibility = View.VISIBLE
                            cacheProgressBar.visibility = View.VISIBLE
                            cacheStatusTextView.text = "Preparing to cache ${progress.total} photos..."
                            cacheProgressBar.isIndeterminate = true
                        }
                        is PersistentPhotoCache.CachingProgress.InProgress -> {
                            cacheStatusTextView.visibility = View.VISIBLE
                            cacheProgressBar.visibility = View.VISIBLE
                            val currentSize = photoRepository.persistentPhotoCache.formatFileSize(progress.currentFileSize)
                            cacheStatusTextView.text = "Caching photos: ${progress.completed}/${progress.total} ($currentSize)"
                            cacheProgressBar.isIndeterminate = false
                            cacheProgressBar.progress = (progress.progress * 100).toInt()
                        }
                        is PersistentPhotoCache.CachingProgress.Complete -> {
                            cacheStatusTextView.visibility = View.VISIBLE
                            cacheProgressBar.visibility = View.VISIBLE
                            cacheProgressBar.progress = 100 // Ensure progress bar shows 100%

                            val totalSize = photoRepository.persistentPhotoCache.formatFileSize(progress.totalSizeBytes)
                            cacheStatusTextView.text = "Caching Complete: ${progress.succeeded} photos (${totalSize})"

                            // Hide status after delay
                            launch {
                                delay(3000)
                                if (binding.viewPager.currentItem == 0) { // Only hide if still on first tab
                                    cacheStatusTextView.visibility = View.GONE
                                    cacheProgressBar.visibility = View.GONE
                                }
                            }
                        }
                        is PersistentPhotoCache.CachingProgress.Failed -> {
                            cacheStatusTextView.visibility = View.VISIBLE
                            cacheProgressBar.visibility = View.GONE
                            cacheStatusTextView.text = "Caching failed: ${progress.reason}"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in observeCacheStatus", e)
            }
        }
    }

    private fun updateButtonsVisibility(position: Int) {
        val hasPhotos = viewModel.photos.value.isNotEmpty()
        val selectedCount = viewModel.selectedCount.value

        when (position) {
            0 -> { // Sources tab
                binding.createAlbumFab.hide()
            }
            1 -> { // Photos tab
                // Show FAB on Photos tab if photos are selected
                if (selectedCount > 0) {
                    binding.createAlbumFab.show()
                } else {
                    binding.createAlbumFab.hide()
                }
            }
            2 -> { // Virtual Albums tab
                // Always hide FAB in Virtual Albums tab, regardless of selection
                binding.createAlbumFab.hide()
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
        showDeleteConfirmationDialogSafely()
    }
}