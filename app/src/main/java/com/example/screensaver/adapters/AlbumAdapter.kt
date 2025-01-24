package com.example.screensaver.adapters

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        private var currentLoadingJob: Any? = null

        fun bind(album: Album) {
            titleTextView.text = album.title
            setPhotoCount(album.mediaItemsCount)
            loadAlbumCover(album)
            updateSelectionState(album)
            setupClickListener(album)
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
                .error(R.drawable.placeholder_album_error) // Add a specific error placeholder
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .timeout(10000) // Add a timeout of 10 seconds
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
            val isSelected = getIsSelected(album)
            animateSelectionState(isSelected)
            updateElevation(isSelected)
        }

        private fun getIsSelected(album: Album): Boolean {
            return itemView.context.getSharedPreferences("screensaver_prefs", 0)
                .getStringSet("selected_albums", setOf())
                ?.contains(album.id) == true
        }

        private fun animateSelectionState(isSelected: Boolean) {
            selectedOverlay.apply {
                visibility = View.VISIBLE
                animate()
                    .alpha(if (isSelected) OVERLAY_ALPHA else 0f)
                    .setDuration(ANIMATION_DURATION)
                    .start()
            }

            checkmarkIcon.apply {
                visibility = View.VISIBLE
                animate()
                    .alpha(if (isSelected) 1f else 0f)
                    .setDuration(ANIMATION_DURATION)
                    .start()
            }
        }

        private fun updateElevation(isSelected: Boolean) {
            val resources = itemView.resources
            itemView.elevation = if (isSelected) {
                resources.getDimension(R.dimen.card_elevation_selected)
            } else {
                resources.getDimension(R.dimen.card_elevation_normal)
            }
        }

        private fun setupClickListener(album: Album) {
            itemView.setOnClickListener { view ->
                view.isEnabled = false
                view.isPressed = true
                onAlbumClick(album)

                view.postDelayed({
                    view.isPressed = false
                    view.isEnabled = true
                }, CLICK_DELAY)
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