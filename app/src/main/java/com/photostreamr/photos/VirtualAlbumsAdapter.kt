package com.photostreamr.photos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.RequestManager
import com.photostreamr.R
import com.photostreamr.databinding.ItemVirtualAlbumBinding
import android.net.Uri

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
                // Set basic info
                albumName.text = album.name
                photoCount.text = "${album.photoUris.size} photos"

                // Set selection state
                albumSelector.isChecked = album.isSelected
                selectedOverlay.visibility = if (album.isSelected) View.VISIBLE else View.GONE

                // Set accessibility descriptions
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

                // Load album cover image
                album.photoUris.firstOrNull()?.let { coverUri ->
                    glide.load(Uri.parse(coverUri))
                        .centerCrop()
                        .into(albumCover)
                }

                // Set click listener on the entire item for selection/deselection
                albumContainer.setOnClickListener {
                    val newState = !album.isSelected
                    onAlbumSelectionChanged(album, newState)
                    // Update UI immediately for better user feedback
                    albumSelector.isChecked = newState
                    selectedOverlay.visibility = if (newState) View.VISIBLE else View.GONE
                }

                // Set click listener for options button only
                albumOptionsButton.setOnClickListener {
                    onAlbumOptionsClick(album)
                }

                // Prevent checkbox from receiving focus or clicks (handled by container)
                albumSelector.isClickable = false
                albumSelector.isFocusable = false
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