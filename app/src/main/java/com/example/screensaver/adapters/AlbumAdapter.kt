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
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.screensaver.R
import com.example.screensaver.models.Album


class AlbumAdapter(
    private val glideRequestManager: RequestManager,
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.AlbumViewHolder>(AlbumDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    private val holders = mutableSetOf<AlbumViewHolder>()

    companion object {
        private const val TAG = "AlbumAdapter"
        private const val DEBUG = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        return AlbumViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_album, parent, false),
            glideRequestManager,
            onAlbumClick
        ).also { holders.add(it) }
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AlbumViewHolder) {
        super.onViewRecycled(holder)
        holders.remove(holder)
        if (DEBUG) Log.d(TAG, "View recycled at position: ${holder.bindingAdapterPosition}")
        // Don't clear the image here
    }

    override fun onViewDetachedFromWindow(holder: AlbumViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (DEBUG) Log.d(TAG, "View detached at position: ${holder.bindingAdapterPosition}")
        // Don't clear the image here
    }

    class AlbumViewHolder(
        itemView: View,
        private val glideRequestManager: RequestManager,
        private val onAlbumClick: (Album) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleTextView: TextView = itemView.findViewById(R.id.albumTitle)
        private val countTextView: TextView = itemView.findViewById(R.id.photoCount)
        private val coverImageView: ImageView = itemView.findViewById(R.id.albumCover)
        private val selectedOverlay: View = itemView.findViewById(R.id.selectedOverlay)
        private val checkmarkIcon: ImageView = itemView.findViewById(R.id.checkmark)
        private val checkbox: CheckBox = itemView.findViewById(R.id.albumCheckbox)

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
            titleTextView.text = album.title
            setPhotoCount(album.mediaItemsCount)
            loadAlbumCover(album)
            updateSelectionState(album)
        }

        private fun setPhotoCount(count: Int) {
            countTextView.text = itemView.context.resources.getQuantityString(
                R.plurals.photo_count,
                count,
                count
            )
        }

        private fun loadAlbumCover(album: Album) {
            if (bindingAdapterPosition == RecyclerView.NO_POSITION) {
                if (DEBUG) Log.d(TAG, "Skip loading: invalid position for album ${album.title}")
                return
            }

            if (DEBUG) Log.d(TAG, "Starting to load cover for album: ${album.title}")

            if (album.coverPhotoUrl?.isNotEmpty() == true) {
                if (DEBUG) Log.d(TAG, "Loading URL: ${album.coverPhotoUrl} for album: ${album.title}")

                glideRequestManager
                    .load(album.coverPhotoUrl)
                    .placeholder(R.drawable.placeholder_album)
                    .error(R.drawable.placeholder_album_error)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .dontAnimate()
                    .into(coverImageView)
            } else {
                if (DEBUG) Log.w(TAG, "No cover URL for album: ${album.title}")
                coverImageView.setImageResource(R.drawable.placeholder_album)
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