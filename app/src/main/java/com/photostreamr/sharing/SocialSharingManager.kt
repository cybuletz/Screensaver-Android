package com.photostreamr.sharing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.photostreamr.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for handling social media sharing functionality
 * Created by cybuletz on 2025-05-27
 */
@Singleton
class SocialSharingManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SocialSharingManager"
        private const val SHARED_PHOTOS_DIR = "shared_photos"
        private const val PHOTO_FILE_PREFIX = "slideshow_photo_"
        private const val PHOTO_FILE_EXTENSION = ".jpg"
        private const val JPEG_QUALITY = 90
    }

    /**
     * Share the current view content, excluding specified UI elements
     */
    suspend fun shareCurrentView(
        containerView: View,
        elementsToHide: List<View> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting share process for current view")

            // Capture view while hiding UI elements
            val bitmap = withContext(Dispatchers.Main) {
                captureViewWithoutUIElements(containerView, elementsToHide)
            }

            if (bitmap == null) {
                Log.e(TAG, "Failed to capture view as bitmap")
                withContext(Dispatchers.Main) {
                    showErrorToast("Unable to capture photo for sharing")
                }
                return@withContext false
            }

            // Save bitmap to temporary file
            val photoFile = saveBitmapToTempFile(bitmap)
            if (photoFile == null) {
                Log.e(TAG, "Failed to save bitmap to temporary file")
                withContext(Dispatchers.Main) {
                    showErrorToast("Unable to prepare photo for sharing")
                }
                return@withContext false
            }

            // Create share intent
            val shareUri = getFileUri(photoFile)
            if (shareUri == null) {
                Log.e(TAG, "Failed to get file URI for sharing")
                withContext(Dispatchers.Main) {
                    showErrorToast("Unable to prepare photo for sharing")
                }
                return@withContext false
            }

            // Launch share dialog on main thread
            withContext(Dispatchers.Main) {
                launchShareDialog(shareUri)
            }

            Log.d(TAG, "Share process completed successfully")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Error during share process", e)
            withContext(Dispatchers.Main) {
                showErrorToast("Error sharing photo: ${e.message}")
            }
            return@withContext false
        }
    }

    /**
     * Capture view while temporarily hiding UI elements
     */
    private fun captureViewWithoutUIElements(
        view: View,
        elementsToHide: List<View>
    ): Bitmap? {
        return try {
            if (view.width <= 0 || view.height <= 0) {
                Log.e(TAG, "View has invalid dimensions: ${view.width}x${view.height}")
                return null
            }

            // Store original visibility states
            val originalVisibilities = mutableMapOf<View, Int>()

            // Hide UI elements temporarily
            elementsToHide.forEach { element ->
                originalVisibilities[element] = element.visibility
                element.visibility = View.GONE
            }

            // Capture the view
            val bitmap = Bitmap.createBitmap(
                view.width,
                view.height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bitmap)
            view.draw(canvas)

            // Restore original visibility states
            originalVisibilities.forEach { (element, originalVisibility) ->
                element.visibility = originalVisibility
            }

            Log.d(TAG, "Successfully captured view as bitmap: ${bitmap.width}x${bitmap.height}")
            bitmap

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing view as bitmap", e)
            null
        }
    }

    /**
     * Save bitmap to temporary file for sharing
     */
    private fun saveBitmapToTempFile(bitmap: Bitmap): File? {
        return try {
            // Create shared photos directory
            val sharedPhotosDir = File(context.cacheDir, SHARED_PHOTOS_DIR)
            if (!sharedPhotosDir.exists()) {
                sharedPhotosDir.mkdirs()
            }

            // Clean up old temporary files
            cleanupOldTempFiles(sharedPhotosDir)

            // Create new temporary file
            val timestamp = System.currentTimeMillis()
            val photoFile = File(sharedPhotosDir, "$PHOTO_FILE_PREFIX$timestamp$PHOTO_FILE_EXTENSION")

            // Save bitmap to file
            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }

            Log.d(TAG, "Saved bitmap to temporary file: ${photoFile.absolutePath}")
            photoFile

        } catch (e: IOException) {
            Log.e(TAG, "Error saving bitmap to temporary file", e)
            null
        }
    }

    /**
     * Clean up old temporary sharing files
     */
    private fun cleanupOldTempFiles(directory: File) {
        try {
            val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago

            directory.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        Log.d(TAG, "Cleaned up old temp file: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up old temp files", e)
        }
    }

    /**
     * Get file URI for sharing using FileProvider
     */
    private fun getFileUri(file: File): Uri? {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file URI", e)
            null
        }
    }

    /**
     * Launch Android's native share dialog
     */
    private fun launchShareDialog(uri: Uri) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_slideshow_photo))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooserIntent = Intent.createChooser(shareIntent, context.getString(R.string.share_slideshow_photo))
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(chooserIntent)
            Log.d(TAG, "Launched share dialog successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error launching share dialog", e)
            showErrorToast("No apps available for sharing")
        }
    }

    /**
     * Show error toast message
     */
    private fun showErrorToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}