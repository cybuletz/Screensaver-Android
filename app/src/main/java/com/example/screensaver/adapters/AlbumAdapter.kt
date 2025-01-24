package com.example.screensaver.adapters

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox  // Added this import
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.screensaver.R
import com.example.screensaver.models.Album

class AlbumAdapter(
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    companion object {
        private const val TAG = "AlbumAdapter"
        private const val ANIMATION_DURATION = 200L
        private const val CLICK_DELAY = 100L
        private const val OVERLAY_ALPHA = 0.5f
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        return AlbumViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_album, parent, false),
            onAlbumClick
        )
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
        private val checkbox: CheckBox = itemView.findViewById(R.id.albumCheckbox)

        private var currentLoadingJob: Any? = null

        fun bind(album: Album) {
            titleTextView.text = album.title
            setPhotoCount(album.mediaItemsCount)
            loadAlbumCover(album)
            updateSelectionState(album)
            setupCheckbox(album)  // Changed from setupClickListener to setupCheckbox
        }

        private fun setPhotoCount(count: Int) {
            countTextView.text = itemView.context.resources.getQuantityString(
                R.plurals.photo_count,
                count,
                count
            )
        }

        private fun loadAlbumCover(album: Album) {
            cancelCurrentLoading()

            currentLoadingJob = Glide.with(itemView.context)
                .load(album.coverPhotoUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.placeholder_album)
                .error(R.drawable.placeholder_album_error)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .timeout(10000)
                .centerCrop()
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?
                    ) {
                        if (adapterPosition != RecyclerView.NO_POSITION) {
                            coverImageView.setImageDrawable(resource)
                            Log.d(TAG, "Cover loaded for ${album.title}")
                        }
                        currentLoadingJob = null
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        if (adapterPosition != RecyclerView.NO_POSITION) {
                            coverImageView.setImageDrawable(placeholder)
                        }
                        currentLoadingJob = null
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        if (adapterPosition != RecyclerView.NO_POSITION) {
                            coverImageView.setImageDrawable(errorDrawable)
                            Log.e(TAG, "Failed to load cover for ${album.title}")
                        }
                        currentLoadingJob = null
                    }
                })
        }

        private fun cancelCurrentLoading() {
            currentLoadingJob?.let {
                Glide.with(itemView.context).clear(coverImageView)
                currentLoadingJob = null
            }
        }

        private fun updateSelectionState(album: Album) {
            checkbox.isChecked = album.isSelected  // Update checkbox state
            selectedOverlay.alpha = if (album.isSelected) OVERLAY_ALPHA else 0f
            checkmarkIcon.alpha = if (album.isSelected) 1f else 0f
            updateElevation(album.isSelected)
        }

        private fun updateElevation(isSelected: Boolean) {
            val resources = itemView.resources
            itemView.elevation = if (isSelected) {
                resources.getDimension(R.dimen.card_elevation_selected)
            } else {
                resources.getDimension(R.dimen.card_elevation_normal)
            }
        }

        private fun setupCheckbox(album: Album) {
            // Remove click listener from itemView since we're using checkbox
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    onAlbumClick(album.copy(isSelected = isChecked))
                }
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