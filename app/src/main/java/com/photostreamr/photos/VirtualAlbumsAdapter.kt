package com.photostreamr.photos

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.photostreamr.R
import com.photostreamr.databinding.ItemVirtualAlbumBinding
import android.net.Uri  // Add this import for URI handling

class VirtualAlbumsAdapter(
    private val glide: RequestManager,
    private val onAlbumClick: (VirtualAlbum) -> Unit,
    private val onAlbumOptionsClick: (VirtualAlbum) -> Unit,
    private val onAlbumSelectionChanged: (VirtualAlbum, Boolean) -> Unit
) : ListAdapter<VirtualAlbum, VirtualAlbumsAdapter.VirtualAlbumViewHolder>(VirtualAlbumDiffCallback()) {

    inner class VirtualAlbumViewHolder(
        private val binding: ItemVirtualAlbumBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(album: VirtualAlbum) {
            binding.apply {
                albumName.text = album.name
                photoCount.text = "${album.photoUris.size} photos"
                albumSelector.isChecked = album.isSelected

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

                album.photoUris.firstOrNull()?.let { coverUri ->
                    glide.load(Uri.parse(coverUri))
                        .centerCrop()
                        .into(albumCover)
                }

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

    private class VirtualAlbumDiffCallback : DiffUtil.ItemCallback<VirtualAlbum>() {
        override fun areItemsTheSame(
            oldItem: VirtualAlbum,
            newItem: VirtualAlbum
        ) = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: VirtualAlbum,
            newItem: VirtualAlbum
        ) = oldItem == newItem
    }
}