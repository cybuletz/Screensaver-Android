package com.example.screensaver.photomanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.screensaver.databinding.ItemCollectionBinding

class CollectionListAdapter(
    private val onCollectionClick: (PhotoCollectionManager.Collection) -> Unit,
    private val onDeleteClick: (PhotoCollectionManager.Collection) -> Unit
) : ListAdapter<PhotoCollectionManager.Collection, CollectionListAdapter.ViewHolder>(CollectionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCollectionBinding.inflate(
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
        private val binding: ItemCollectionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                onCollectionClick(getItem(bindingAdapterPosition))
            }
            binding.deleteButton.setOnClickListener {
                onDeleteClick(getItem(bindingAdapterPosition))
            }
        }

        fun bind(collection: PhotoCollectionManager.Collection) {
            binding.apply {
                collectionName.text = collection.name
                collectionDescription.text = collection.description
                photoCount.text = itemView.context.resources.getQuantityString(
                    com.example.screensaver.R.plurals.photo_count,
                    collection.photoRefs.size,
                    collection.photoRefs.size
                )
            }
        }
    }

    private class CollectionDiffCallback : DiffUtil.ItemCallback<PhotoCollectionManager.Collection>() {
        override fun areItemsTheSame(
            oldItem: PhotoCollectionManager.Collection,
            newItem: PhotoCollectionManager.Collection
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: PhotoCollectionManager.Collection,
            newItem: PhotoCollectionManager.Collection
        ): Boolean {
            return oldItem == newItem
        }
    }
}