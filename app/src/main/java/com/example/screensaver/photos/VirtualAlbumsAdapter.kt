package com.example.screensaver.photos

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.example.screensaver.databinding.ItemVirtualAlbumBinding

class VirtualAlbumsAdapter(
    private val glide: RequestManager,
    private val onAlbumClick: (PhotoManagerViewModel.VirtualAlbum) -> Unit,
    private val onAlbumOptionsClick: (PhotoManagerViewModel.VirtualAlbum) -> Unit
) : ListAdapter<PhotoManagerViewModel.VirtualAlbum, VirtualAlbumsAdapter.VirtualAlbumViewHolder>(VirtualAlbumDiffCallback()) {

    inner class VirtualAlbumViewHolder(
        private val binding: ItemVirtualAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: PhotoManagerViewModel.VirtualAlbum) {
            binding.albumName.text = album.name
            binding.photoCount.text = "${album.photoUris.size} photos"

            // Load cover photo if available
            album.photoUris.firstOrNull()?.let { coverUri ->
                glide.load(coverUri)
                    .centerCrop()
                    .into(binding.albumCover)
            }

            itemView.setOnClickListener {
                onAlbumClick(album)
            }

            itemView.setOnLongClickListener {
                onAlbumOptionsClick(album)
                true
            }

            binding.albumOptionsButton.setOnClickListener {
                onAlbumOptionsClick(album)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VirtualAlbumViewHolder {
        return VirtualAlbumViewHolder(
            ItemVirtualAlbumBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: VirtualAlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private class VirtualAlbumDiffCallback : DiffUtil.ItemCallback<PhotoManagerViewModel.VirtualAlbum>() {
        override fun areItemsTheSame(
            oldItem: PhotoManagerViewModel.VirtualAlbum,
            newItem: PhotoManagerViewModel.VirtualAlbum
        ) = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: PhotoManagerViewModel.VirtualAlbum,
            newItem: PhotoManagerViewModel.VirtualAlbum
        ) = oldItem == newItem
    }
}