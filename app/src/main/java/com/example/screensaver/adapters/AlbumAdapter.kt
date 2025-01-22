package com.example.screensaver.adapters

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.screensaver.R
import com.example.screensaver.models.Album
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class AlbumAdapter(
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    companion object {
        private const val TAG = "AlbumAdapter"
    }

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
        private val checkmarkIcon: ImageView = itemView.findViewById(R.id.checkmark)

        fun bind(album: Album) {
            titleTextView.text = album.title
            countTextView.text = itemView.context.resources.getQuantityString(
                R.plurals.photo_count,
                album.mediaItemsCount,
                album.mediaItemsCount
            )

            loadAlbumCover(album)
            updateSelectionState(album)
            setupClickListener(album)
        }

        private fun loadAlbumCover(album: Album) {
            Log.d(TAG, "Loading cover for album: ${album.title}")
            Glide.with(itemView.context)
                .load(album.coverPhotoUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.placeholder_album)
                .error(R.drawable.placeholder_album)
                .centerCrop()
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        coverImageView.setImageDrawable(resource)
                        Log.d(TAG, "Successfully loaded cover for album: ${album.title}")
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        coverImageView.setImageDrawable(placeholder)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        coverImageView.setImageDrawable(errorDrawable)
                        Log.e(TAG, "Failed to load cover for album: ${album.title}")
                    }
                })
        }

        private fun updateSelectionState(album: Album) {
            val isSelected = itemView.context.getSharedPreferences("screensaver_prefs", 0)
                .getStringSet("selected_albums", setOf())
                ?.contains(album.id) == true

            selectedOverlay.apply {
                alpha = if (isSelected) 0.5f else 0f
                visibility = View.VISIBLE
                animate()
                    .alpha(if (isSelected) 0.5f else 0f)
                    .setDuration(200)
                    .start()
            }

            checkmarkIcon.apply {
                alpha = if (isSelected) 1f else 0f
                visibility = View.VISIBLE
                animate()
                    .alpha(if (isSelected) 1f else 0f)
                    .setDuration(200)
                    .start()
            }

            itemView.elevation = if (isSelected) {
                itemView.resources.getDimension(R.dimen.card_elevation_selected)
            } else {
                itemView.resources.getDimension(R.dimen.card_elevation_normal)
            }
        }

        private fun setupClickListener(album: Album) {
            itemView.setOnClickListener {
                it.isPressed = true
                it.postDelayed({ it.isPressed = false }, 100)
                onAlbumClick(album)
            }
        }
    }

    private class AlbumDiffCallback : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean =
            oldItem == newItem && oldItem.isSelected == newItem.isSelected
    }
}