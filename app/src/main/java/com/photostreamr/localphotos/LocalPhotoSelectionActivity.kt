package com.photostreamr.localphotos

import android.Manifest
import android.os.Bundle
import android.provider.MediaStore
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.photostreamr.R
import com.photostreamr.databinding.ActivityLocalPhotoSelectionBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.view.Menu
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.photostreamr.databinding.ItemFolderBinding
import kotlinx.coroutines.cancelChildren

@AndroidEntryPoint
class LocalPhotoSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalPhotoSelectionBinding
    private lateinit var adapter: LocalPhotoAdapter

    // Added for folder navigation
    private var currentFolderId: String? = null
    private var folderList: List<PhotoFolder> = emptyList()
    private var selectedPhotos = mutableListOf<LocalPhoto>()

    private val pickMultipleMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) {
            // Process the selected URIs directly
            val photos = uris.mapIndexed { index, uri ->
                LocalPhoto(index.toLong(), uri, uri.lastPathSegment ?: "Photo ${index + 1}")
            }
            adapter.submitList(photos)
            binding.recyclerView.isVisible = true
            updateSelectionCount(adapter.getSelectedPhotos().size)
        } else {
            // Handle case where no photos were selected
            showNoPhotosMessage()
        }
    }

    companion object {
        private const val TAG = "LocalPhotoSelection"
        private const val REQUEST_PERMISSIONS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Activity created")

        binding = ActivityLocalPhotoSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupSaveButton()
        checkPermissions()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.local_photo_selection_menu, menu)
        // Only show select all when viewing photos, not folders
        menu.findItem(R.id.action_select_all)?.isVisible = currentFolderId != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> {
                selectAllInCurrentFolder()
                true
            }
            android.R.id.home -> {
                if (currentFolderId != null) {
                    // If in a folder, go back to folder selection
                    currentFolderId = null
                    invalidateOptionsMenu() // Update menu
                    loadPhotos()
                } else {
                    // Otherwise exit
                    finish()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun selectAllInCurrentFolder() {
        if (::adapter.isInitialized && currentFolderId != null) {
            val allSelected = adapter.getSelectedPhotos().size == adapter.itemCount

            if (allSelected) {
                // If all are selected, unselect all
                val photos = adapter.currentList.map { it.copy(isSelected = false) }
                adapter.submitList(photos)
                adapter.setPreselectedPhotos(emptyList())
            } else {
                // Otherwise select all
                val allUris = adapter.currentList.map { it.uri }
                adapter.setPreselectedPhotos(allUris)
            }
            adapter.notifyDataSetChanged()
            updateSelectionCount(adapter.getSelectedPhotos().size)
        }
    }

    private fun setupSaveButton() {
        binding.saveButton.setOnClickListener {
            Log.d(TAG, "Save button clicked")
            saveSelectedPhotos()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.select_folder)
        }
    }

    private fun setupRecyclerView() {
        adapter = LocalPhotoAdapter { isSelected ->
            updateSelectionCount(adapter.getSelectedPhotos().size)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@LocalPhotoSelectionActivity, 3)
            adapter = this@LocalPhotoSelectionActivity.adapter
            setHasFixedSize(true)
        }

        // Restore previously selected photos if any
        val previouslySelectedPhotos = intent.getStringArrayListExtra("selected_photos")
        previouslySelectedPhotos?.let { uris ->
            adapter.setPreselectedPhotos(uris.mapNotNull { Uri.parse(it) })
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Use READ_MEDIA_IMAGES permission
            val permission = Manifest.permission.READ_MEDIA_IMAGES
            when {
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                    loadPhotos()
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    showPermissionRationale(permission)
                }
                else -> {
                    requestPermissions(arrayOf(permission), REQUEST_PERMISSIONS)
                }
            }
        } else {
            // For Android 12 and below, check READ_EXTERNAL_STORAGE permission
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED -> {
                    loadPhotos()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) -> {
                    showPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                else -> {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_PERMISSIONS)
                }
            }
        }
    }

    private fun showPermissionRationale(permission: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.storage_permission_rationale)
            .setPositiveButton(R.string.grant) { dialog, _ ->
                requestPermissions(arrayOf(permission), REQUEST_PERMISSIONS)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                finish()
                dialog.dismiss()
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadPhotos()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        }
    }

    private fun loadPhotos() {
        lifecycleScope.launch {
            binding.progressBar.isVisible = true
            binding.recyclerView.isVisible = false
            binding.emptyView.isVisible = false

            try {
                if (currentFolderId == null) {
                    // Load folders
                    folderList = withContext(Dispatchers.IO) {
                        getPhotoFolders()
                    }

                    if (folderList.isEmpty()) {
                        showNoPhotosMessage()
                    } else {
                        showFolders()
                    }
                } else {
                    // Load photos from selected folder
                    val photos = withContext(Dispatchers.IO) {
                        getPhotosFromFolder(currentFolderId!!)
                    }

                    if (photos.isEmpty()) {
                        showNoPhotosMessage()
                    } else {
                        showPhotos(photos)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                showError(getString(R.string.error_loading_photos))
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun showFolders() {
        supportActionBar?.title = getString(R.string.select_folder)

        val folderAdapter = FolderAdapter(folderList) { folder ->
            showFolderOptions(folder)
        }

        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@LocalPhotoSelectionActivity, 2)
            adapter = folderAdapter
        }

        binding.recyclerView.isVisible = true
        binding.saveButton.isVisible = false
        binding.selectionCount.isVisible = false
    }

    private fun showPhotos(photos: List<LocalPhoto>) {
        supportActionBar?.title = folderList.find { it.id == currentFolderId }?.name
            ?: getString(R.string.select_photos)

        adapter.submitList(photos)
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@LocalPhotoSelectionActivity, 3)
            adapter = this@LocalPhotoSelectionActivity.adapter
        }

        binding.recyclerView.isVisible = true
        binding.saveButton.isVisible = true
        binding.selectionCount.isVisible = true
        invalidateOptionsMenu() // Update menu to show "Select All"

        updateSelectionCount(adapter.getSelectedPhotos().size)
    }

    private fun showFolderOptions(folder: PhotoFolder) {
        MaterialAlertDialogBuilder(this)
            .setTitle(folder.name)
            .setMessage(getString(R.string.folder_option_message, folder.count))
            .setPositiveButton(R.string.select_all) { dialog, _ ->
                selectAllFromFolder(folder)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.browse) { dialog, _ ->
                // Browse the folder
                currentFolderId = folder.id
                loadPhotos()
                dialog.dismiss()
            }
            .show()
    }

    private fun selectAllFromFolder(folder: PhotoFolder) {
        lifecycleScope.launch {
            binding.progressBar.isVisible = true

            try {
                val photos = withContext(Dispatchers.IO) {
                    getPhotosFromFolder(folder.id)
                }

                if (photos.isNotEmpty()) {
                    // Add all photos from this folder to our selected list
                    selectedPhotos.addAll(photos)

                    // Show toast with count
                    Toast.makeText(
                        this@LocalPhotoSelectionActivity,
                        getString(R.string.added_photos_from_folder, photos.size, folder.name),
                        Toast.LENGTH_SHORT
                    ).show()

                    // Update save button visibility and selection count
                    binding.saveButton.isVisible = true
                    binding.selectionCount.isVisible = true
                    updateSelectionCount(selectedPhotos.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error selecting folder photos", e)
                showError(getString(R.string.error_selecting_photos))
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private suspend fun getPhotoFolders(): List<PhotoFolder> {
        val folders = mutableMapOf<String, PhotoFolder>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val bucketId = cursor.getString(bucketIdColumn)
                val bucketName = cursor.getString(bucketNameColumn) ?: "Unknown"

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                if (!folders.containsKey(bucketId)) {
                    folders[bucketId] = PhotoFolder(
                        id = bucketId,
                        name = bucketName,
                        coverUri = contentUri,
                        count = 1
                    )
                } else {
                    val folder = folders[bucketId]!!
                    folders[bucketId] = folder.copy(count = folder.count + 1)
                }
            }
        }

        return folders.values.sortedBy { it.name }
    }

    private suspend fun getPhotosFromFolder(folderId: String): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(folderId)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                photos.add(LocalPhoto(id, contentUri, name))
            }
        }

        return photos
    }

    private fun updateSelectionCount(count: Int) {
        binding.selectionCount.text = getString(R.string.photos_selected, count)
        binding.saveButton.isEnabled = count > 0
    }

    private fun showNoPhotosMessage() {
        binding.recyclerView.isVisible = false
        binding.emptyView.isVisible = true
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_denied)
            .setMessage(R.string.storage_permission_denied_message)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                finish()
                dialog.dismiss()
            }
            .show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentFolderId != null) {
            // If in a folder, go back to folder selection
            currentFolderId = null
            invalidateOptionsMenu() // Update menu
            loadPhotos()
        } else {
            // Clean up before finishing
            releasePhotoReferences()
            super.onBackPressed()
        }
    }

    private fun saveSelectedPhotos() {
        // Get selected photos from adapter if we're in a folder view
        var photosToSave = if (currentFolderId != null) {
            adapter.getSelectedPhotos()
        } else {
            // Otherwise use our accumulated selected photos from all folders
            selectedPhotos
        }

        Log.d(TAG, "Saving ${photosToSave.size} selected photos")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Take persistable permissions for each URI
                val processedUris = photosToSave.mapNotNull { photo ->
                    try {
                        val uri = photo.uri
                        // Only take persistable permissions for non-MediaStore URIs that need them
                        if (!uri.toString().startsWith("content://media/") &&
                            !uri.toString().startsWith("content://com.android.providers")) {
                            try {
                                // Use contentResolver directly from the activity
                                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                contentResolver.takePersistableUriPermission(uri, flags)
                                Log.d(TAG, "Took persistable permission for: $uri")
                            } catch (e: SecurityException) {
                                // This is expected for MediaStore URIs and system content providers
                                Log.d(TAG, "No need for persistable permission for: $uri")
                            }
                        }
                        uri.toString()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing URI: ${photo.uri}", e)
                        photo.uri.toString() // Still include the URI even if there was an error
                    }
                }

                withContext(Dispatchers.Main) {
                    val resultIntent = Intent().apply {
                        putStringArrayListExtra(
                            "selected_photos",
                            ArrayList(processedUris)
                        )
                    }
                    setResult(Activity.RESULT_OK, resultIntent)
                    Log.d(TAG, "Setting result with ${processedUris.size} processed URIs")
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing selected photos", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LocalPhotoSelectionActivity,
                        R.string.error_saving_photos,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Force Glide to clear memory
        Glide.get(this).clearMemory()

        // Encourage garbage collection
        System.gc()

        // Clear adapter reference
        if (::adapter.isInitialized) {
            // Clear any preselected photos
            adapter.setPreselectedPhotos(emptyList())
        }

        // Clear selected photos
        selectedPhotos.clear()
    }

    private fun releasePhotoReferences() {
        // Clear recycler view adapter to release view holders
        binding.recyclerView.adapter = null

        // Cancel any ongoing operations
        lifecycleScope.coroutineContext.cancelChildren()

        // Tell Glide to clear any resources
        Glide.get(this).clearMemory()
    }
}

// Data class for folders
data class PhotoFolder(
    val id: String,
    val name: String,
    val coverUri: Uri,
    val count: Int
)

// Folder adapter for showing folders
class FolderAdapter(
    private val folders: List<PhotoFolder>,
    private val onFolderSelected: (PhotoFolder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val folder = folders[position]
        holder.binding.apply {
            folderName.text = folder.name
            photoCount.text = holder.itemView.context.getString(R.string.photo_count, folder.count)

            // Load folder thumbnail
            Glide.with(holder.itemView.context)
                .load(folder.coverUri)
                .placeholder(R.drawable.placeholder_album)
                .error(R.drawable.placeholder_album_error)
                .centerCrop()
                .into(folderThumbnail)

            root.setOnClickListener {
                onFolderSelected(folder)
            }
        }
    }

    override fun getItemCount() = folders.size
}