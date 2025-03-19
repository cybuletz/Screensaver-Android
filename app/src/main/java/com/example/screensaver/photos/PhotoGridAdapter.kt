package com.example.screensaver.photos

import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.screensaver.R
import com.example.screensaver.databinding.ItemPhotoGridBinding
import javax.inject.Inject

typealias ManagedPhotoType = com.example.screensaver.photos.PhotoManagerViewModel.ManagedPhoto

class PhotoGridAdapter @Inject constructor(
    private val glide: RequestManager,
    private val photoUriManager: PhotoUriManager,
    private val onPhotoClick: (ManagedPhotoType) -> Unit,
    private val onPhotoLoadError: ((ManagedPhotoType, Exception?) -> Unit)? = null
) : ListAdapter<ManagedPhotoType, PhotoGridAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

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

            // Hide error indicator initially
            binding.errorIndicator.visibility = View.GONE
            binding.photoImage.setImageResource(R.drawable.ic_photo_placeholder)

            try {
                val uri = Uri.parse(photo.uri)

                // Check URI permissions before loading
                val hasPermission = photoUriManager.hasValidPermission(uri)
                val isGooglePhotosUri = photoUriManager.isGooglePhotosUri(uri)

                Log.d(TAG, "Loading photo URI: $uri, hasPermission: $hasPermission, isGooglePhotos: $isGooglePhotosUri")

                // Don't clear previous image immediately to prevent flashing
                glide.load(uri)
                    .thumbnail(0.1f)  // Add thumbnail loading
                    .placeholder(R.drawable.ic_photo_placeholder)
                    .error(R.drawable.ic_error)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .override(PREVIEW_SIZE)
                    .transform(
                        CenterCrop(),
                        RoundedCorners(8)
                    )
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.e(TAG, "Failed to load image: ${photo.uri}", e)
                            binding.errorIndicator.visibility = View.VISIBLE
                            onPhotoLoadError?.invoke(photo, e)
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            binding.errorIndicator.visibility = View.GONE
                            return false
                        }
                    })
                    .into(binding.photoImage)

            } catch (e: Exception) {
                Log.e(TAG, "Error loading photo: ${photo.uri}", e)
                binding.errorIndicator.visibility = View.VISIBLE
                onPhotoLoadError?.invoke(photo, e)
            }
        }
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        glide.clear(holder.itemView)
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