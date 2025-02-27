package com.example.screensaver.photos

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.example.screensaver.R
import com.example.screensaver.databinding.ItemPhotoGridBinding
import javax.inject.Inject

typealias ManagedPhotoType = com.example.screensaver.photos.PhotoManagerViewModel.ManagedPhoto

class PhotoGridAdapter @Inject constructor(
    private val glide: RequestManager,
    private val onPhotoClick: (ManagedPhotoType) -> Unit
) : ListAdapter<ManagedPhotoType, PhotoGridAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    companion object {
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
            glide.load(photo.uri)
                .placeholder(R.drawable.ic_photo_placeholder)
                .into(binding.photoImage)

            binding.sourceIcon.setImageResource(when {
                photo.uri.startsWith("https://photos.google.com") -> R.drawable.ic_google_photos
                photo.uri.startsWith("content://") -> R.drawable.ic_local_photo
                else -> R.drawable.ic_virtual_album
            })

            binding.selectionOverlay.isVisible = photo.isSelected
            binding.selectionCheckbox.isChecked = photo.isSelected
        }
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