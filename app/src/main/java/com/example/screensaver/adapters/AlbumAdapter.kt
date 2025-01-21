package com.example.screensaver.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.screensaver.R
import com.example.screensaver.models.Album

class AlbumAdapter(
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view, onAlbumClick)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class AlbumViewHolder(
        itemView: View,
        private val onAlbumClick: (Album) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.albumTitle)
        private val countTextView: TextView = itemView.findViewById(R.id.photoCount)
        private val coverImageView: ImageView = itemView.findViewById(R.id.albumCover)
        private val selectedOverlay: View = itemView.findViewById(R.id.selectedOverlay)

        fun bind(album: Album) {
            titleTextView.text = album.title
            countTextView.text = "${album.mediaItemsCount} photos"

            album.coverPhotoUrl?.let { url ->
                Glide.with(itemView.context)
                    .load(url)
                    .placeholder(R.drawable.placeholder_album)
                    .centerCrop()
                    .into(coverImageView)
            }

            val isSelected = itemView.context.getSharedPreferences("screensaver_prefs", 0)
                .getStringSet("selected_albums", setOf())
                ?.contains(album.id) == true

            selectedOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                onAlbumClick(album)
            }
        }
    }

    private class AlbumDiffCallback : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean =
            oldItem == newItem
    }
}