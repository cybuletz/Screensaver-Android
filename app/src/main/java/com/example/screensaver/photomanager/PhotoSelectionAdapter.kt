package com.example.screensaver.photomanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.screensaver.R
import com.example.screensaver.databinding.ItemPhotoGridBinding

class PhotoSelectionAdapter(
    private val onPhotoClick: (PhotoSelectionViewModel.PhotoWithSelection, Boolean) -> Unit
) : ListAdapter<PhotoSelectionViewModel.PhotoWithSelection, PhotoSelectionAdapter.ViewHolder>(PhotoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPhotoGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemPhotoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                onPhotoClick(item, !item.isSelected)
            }
        }

        fun bind(item: PhotoSelectionViewModel.PhotoWithSelection) {
            binding.apply {
                Glide.with(photoImage)
                    .load(item.photo.baseUrl)
                    .placeholder(R.drawable.placeholder_image)
                    .centerCrop()
                    .into(photoImage)

                selectionOverlay.isSelected = item.isSelected
                checkIcon.visibility = if (item.isSelected) View.VISIBLE else View.GONE
            }
        }
    }

    private class PhotoDiffCallback : DiffUtil.ItemCallback<PhotoSelectionViewModel.PhotoWithSelection>() {
        override fun areItemsTheSame(
            oldItem: PhotoSelectionViewModel.PhotoWithSelection,
            newItem: PhotoSelectionViewModel.PhotoWithSelection
        ): Boolean {
            return oldItem.photo.id == newItem.photo.id
        }

        override fun areContentsTheSame(
            oldItem: PhotoSelectionViewModel.PhotoWithSelection,
            newItem: PhotoSelectionViewModel.PhotoWithSelection
        ): Boolean {
            return oldItem == newItem
        }
    }
}