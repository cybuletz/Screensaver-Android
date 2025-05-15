package com.photostreamr.photos

import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.R
import com.photostreamr.databinding.ItemPhotoGridBinding
import coil.ImageLoader
import coil.load
import coil.request.CachePolicy
import coil.size.Scale
import coil.transform.RoundedCornersTransformation
import javax.inject.Inject

typealias ManagedPhotoType = com.photostreamr.photos.PhotoManagerViewModel.ManagedPhoto

class PhotoGridAdapter @Inject constructor(
    private val imageLoader: ImageLoader,
    private val photoUriManager: PhotoUriManager,
    private val onPhotoClick: (ManagedPhotoType) -> Unit,
    private val onPhotoLoadError: ((ManagedPhotoType, Exception?) -> Unit)? = null
) : ListAdapter<ManagedPhotoType, PhotoGridAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    private val persistentPhotoCache: PersistentPhotoCache
        get() = photoUriManager.persistentPhotoCache

    companion object {
        private const val TAG = "PhotoGridAdapter"
        private const val PREVIEW_SIZE = 300
    }

    inner class PhotoViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.setOnClickListener {
                getItem(bindingAdapterPosition)?.let { photo ->
                    onPhotoClick(photo)
                }
            }
        }

        fun bind(photo: ManagedPhotoType) {
            Log.d(TAG, "Binding photo: ${photo.id}, URI: ${photo.uri}")

            // Clear any previous loading state
            binding.photoImage.setImageDrawable(null)
            binding.photoImage.background = null

            // Hide error indicator initially
            binding.errorIndicator.visibility = View.GONE

            // Hide file size initially
            binding.photoFileSize.visibility = View.GONE

            // Show placeholder immediately
            binding.photoImage.setImageResource(R.drawable.ic_photo_placeholder)

            try {
                // Get cached URI if available
                val cachedUri = persistentPhotoCache.getCachedPhotoUri(photo.uri)
                val uriToLoad = cachedUri ?: photo.uri
                val uri = Uri.parse(uriToLoad)

                // Check URI permissions before loading
                val hasPermission = if (cachedUri != null) true else photoUriManager.hasValidPermission(uri)
                val isGooglePhotosUri = photoUriManager.isGooglePhotosUri(uri)

                Log.d(TAG, "Loading photo URI: $uri, hasPermission: $hasPermission, isGooglePhotos: $isGooglePhotosUri")

                if (!hasPermission && isGooglePhotosUri) {
                    binding.errorIndicator.visibility = View.VISIBLE
                    return
                }

                // Coil automatically handles clearing previous requests
                binding.photoImage.load(uri, imageLoader) {
                    placeholder(R.drawable.ic_photo_placeholder)
                    error(R.drawable.ic_error)
                    memoryCachePolicy(CachePolicy.ENABLED)
                    diskCachePolicy(CachePolicy.ENABLED)
                    crossfade(false) // Prevent animation issues - equivalent to dontAnimate()
                    size(PREVIEW_SIZE, PREVIEW_SIZE)
                    scale(Scale.FILL) // equivalent to centerCrop
                    transformations(RoundedCornersTransformation(8f))

                    // In the onBindViewHolder method:
                    listener(
                        onSuccess = { _, result ->
                            Log.d(TAG, "Successfully loaded image: ${photo.uri}")

                            // Ensure the image is visible and error is hidden
                            binding.photoImage.visibility = View.VISIBLE
                            binding.errorIndicator.visibility = View.GONE

                            // Display file size if this is a cached photo
                            if (cachedUri != null) {
                                try {
                                    persistentPhotoCache.fileSizes[cachedUri]?.let { size ->
                                        if (size > 0) {
                                            binding.photoFileSize.text = persistentPhotoCache.formatFileSize(size)
                                            binding.photoFileSize.visibility = View.VISIBLE
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error getting file size", e)
                                }
                            }
                        },
                        onError = { _, error ->
                            // Create a new Exception that wraps the original throwable
                            val exception = if (error.throwable is Exception) {
                                error.throwable as Exception
                            } else {
                                Exception("Image loading failed", error.throwable)
                            }

                            Log.e(TAG, "Failed to load image: ${photo.uri}", error.throwable)

                            // Show error indicator
                            binding.errorIndicator.visibility = View.VISIBLE
                            binding.photoFileSize.visibility = View.GONE // Hide file size on error

                            // Notify about error with the correctly typed exception
                            onPhotoLoadError?.invoke(photo, exception)
                        }
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up image load", e)
                binding.photoImage.setImageResource(R.drawable.ic_error)
                binding.errorIndicator.visibility = View.VISIBLE
                binding.photoFileSize.visibility = View.GONE // Hide file size on error
                onPhotoLoadError?.invoke(photo, e)
            }

            binding.sourceIcon.setImageResource(when {
                photo.uri.startsWith("https://photos.google.com") -> R.drawable.ic_google_photos
                photo.uri.startsWith("content://") -> R.drawable.ic_local_photo
                else -> R.drawable.ic_virtual_album
            })

            binding.selectionOverlay.isVisible = photo.isSelected
            binding.selectionCheckbox.isChecked = photo.isSelected
        }
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        // Coil handles this automatically via view lifecycle
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        return PhotoViewHolder(
            ItemPhotoGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private class PhotoDiffCallback : DiffUtil.ItemCallback<ManagedPhotoType>() {
        override fun areItemsTheSame(oldItem: ManagedPhotoType, newItem: ManagedPhotoType): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ManagedPhotoType, newItem: ManagedPhotoType): Boolean {
            return oldItem == newItem
        }
    }
}