package com.example.screensaver.adapters

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
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
        private var isBinding = false


        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val album = getItem(position)
                    checkbox.isChecked = !checkbox.isChecked
                    onAlbumClick(album.copy(isSelected = checkbox.isChecked))
                }
            }
        }

        fun bind(album: Album) {
            if (isBinding) return // Prevent concurrent bindings
            isBinding = true

            try {
                titleTextView.text = album.title
                setPhotoCount(album.mediaItemsCount)
                loadAlbumCover(album)
                updateSelectionState(album)
                checkbox.isChecked = album.isSelected
            } finally {
                isBinding = false
            }
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

            if (bindingAdapterPosition == RecyclerView.NO_POSITION) return

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
                        if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                            coverImageView.post {
                                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                    coverImageView.setImageDrawable(resource)
                                }
                            }
                        }
                        currentLoadingJob = null
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        coverImageView.post {
                            if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                coverImageView.setImageDrawable(placeholder)
                            }
                        }
                        currentLoadingJob = null
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        coverImageView.post {
                            if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                coverImageView.setImageDrawable(errorDrawable)
                                Log.e(TAG, "Failed to load cover for ${album.title}")
                            }
                        }
                        currentLoadingJob = null
                    }
                })
        }

        private fun cancelCurrentLoading() {
            try {
                currentLoadingJob?.let {
                    Glide.with(itemView.context).clear(coverImageView)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling image load", e)
            } finally {
                currentLoadingJob = null
            }
        }

        private fun updateSelectionState(album: Album) {
            checkbox.isChecked = album.isSelected
            selectedOverlay.visibility = if (album.isSelected) View.VISIBLE else View.GONE
            checkmarkIcon.visibility = if (album.isSelected) View.VISIBLE else View.GONE
            updateElevation(album.isSelected)
        }

        private fun updateElevation(isSelected: Boolean) {
            itemView.elevation = itemView.resources.getDimension(
                if (isSelected) R.dimen.card_elevation_selected
                else R.dimen.card_elevation_normal
            )
        }

        private fun getItem(position: Int): Album {
            return (itemView.parent as RecyclerView).adapter?.let {
                (it as AlbumAdapter).getItem(position)
            } ?: throw IllegalStateException("Adapter not found")
        }
    }

    private class AlbumDiffCallback : DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Album, newItem: Album) =
            oldItem == newItem && oldItem.isSelected == newItem.isSelected
    }
}