package com.example.screensaver.photos

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.example.screensaver.R
import com.example.screensaver.databinding.ItemVirtualAlbumBinding

class VirtualAlbumsAdapter(
    private val glide: RequestManager,
    private val onAlbumClick: (PhotoManagerViewModel.VirtualAlbum) -> Unit,
    private val onAlbumOptionsClick: (PhotoManagerViewModel.VirtualAlbum) -> Unit,
    private val onAlbumSelectionChanged: (PhotoManagerViewModel.VirtualAlbum, Boolean) -> Unit
) : ListAdapter<PhotoManagerViewModel.VirtualAlbum, VirtualAlbumsAdapter.VirtualAlbumViewHolder>(VirtualAlbumDiffCallback()) {

    inner class VirtualAlbumViewHolder(
        private val binding: ItemVirtualAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: PhotoManagerViewModel.VirtualAlbum) {
            binding.apply {
                // Basic text setup
                albumName.text = album.name
                photoCount.text = "${album.photoUris.size} photos"
                albumSelector.isChecked = album.isSelected

                // Content descriptions with proper string formatting
                albumOptionsButton.contentDescription = String.format(
                    root.context.getString(R.string.album_options_button_description),
                    album.name
                )
                albumCover.contentDescription = String.format(
                    root.context.getString(R.string.album_cover_description),
                    album.name
                )
                albumSelector.contentDescription = String.format(
                    root.context.getString(R.string.select_album_checkbox_description),
                    album.name
                )

                // Cover photo loading
                album.photoUris.firstOrNull()?.let { coverUri ->
                    glide.load(coverUri)
                        .centerCrop()
                        .into(albumCover)
                }

                // Click listeners
                albumSelector.setOnCheckedChangeListener { _, isChecked ->
                    onAlbumSelectionChanged(album, isChecked)
                }

                itemView.setOnClickListener {
                    onAlbumClick(album)
                }

                albumOptionsButton.setOnClickListener {
                    onAlbumOptionsClick(album)
                }
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