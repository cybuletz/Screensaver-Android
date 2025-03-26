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
import android.widget.Toast
import androidx.core.view.isVisible

@AndroidEntryPoint
class LocalPhotoSelectionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalPhotoSelectionBinding
    private lateinit var adapter: LocalPhotoAdapter

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
            title = getString(R.string.select_photos)
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
        val selectedPhotos = intent.getStringArrayListExtra("selected_photos")
        selectedPhotos?.let { uris ->
            adapter.setPreselectedPhotos(uris.mapNotNull { Uri.parse(it) })
        }
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

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

            try {
                val photos = withContext(Dispatchers.IO) {
                    getLocalPhotos()
                }

                if (photos.isEmpty()) {
                    showNoPhotosMessage()
                } else {
                    adapter.submitList(photos)
                    binding.recyclerView.isVisible = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading photos", e)
                showError(getString(R.string.error_loading_photos))
            } finally {
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun getLocalPhotos(): List<LocalPhoto> {
        val photos = mutableListOf<LocalPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Images.Media.SIZE} > 0"
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveSelectedPhotos() {
        val selectedPhotos = adapter.getSelectedPhotos()
        Log.d(TAG, "Saving ${selectedPhotos.size} selected photos")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Take persistable permissions for each URI
                val processedUris = selectedPhotos.mapNotNull { photo ->
                    try {
                        // Convert to content URI if needed
                        val uri = Uri.parse(photo.uri.toString())
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        uri.toString()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error taking permission for URI: ${photo.uri}", e)
                        photo.uri.toString() // Still include the URI even if permission failed
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
}