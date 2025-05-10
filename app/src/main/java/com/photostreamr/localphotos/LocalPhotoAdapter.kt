package com.photostreamr.localphotos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.CheckBox
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.photostreamr.R
import android.net.Uri
import com.bumptech.glide.load.engine.DiskCacheStrategy

class LocalPhotoAdapter(
    private val onSelectionChanged: (Boolean) -> Unit
) : ListAdapter<LocalPhoto, LocalPhotoAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    private val selectedPhotos = mutableSetOf<Uri>()

    // Add target size options for thumbnails
    private val thumbnailSize = 300

    fun setPreselectedPhotos(photos: List<Uri>) {
        selectedPhotos.clear()
        selectedPhotos.addAll(photos)
        notifyDataSetChanged()
    }

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.photoImage)
        val checkbox: CheckBox = view.findViewById(R.id.photoCheckbox)
        val selectionOverlay: View = view.findViewById(R.id.selectionOverlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_local_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = getItem(position)

        // Use better thumbnail loading approach
        Glide.with(holder.imageView)
            .load(photo.uri)
            .override(thumbnailSize, thumbnailSize) // Specify size to avoid loading full image
            .thumbnail(0.1f)
            .transition(DrawableTransitionOptions.withCrossFade())
            .centerCrop()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // Cache processed resources
            .into(holder.imageView)

        val isSelected = selectedPhotos.contains(photo.uri)
        holder.checkbox.isChecked = isSelected
        holder.selectionOverlay.visibility = if (isSelected) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            if (selectedPhotos.contains(photo.uri)) {
                selectedPhotos.remove(photo.uri)
            } else {
                selectedPhotos.add(photo.uri)
            }
            holder.checkbox.isChecked = selectedPhotos.contains(photo.uri)
            holder.selectionOverlay.visibility = if (selectedPhotos.contains(photo.uri)) View.VISIBLE else View.GONE
            onSelectionChanged(selectedPhotos.contains(photo.uri))
        }
    }

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        // Clear the ImageView to free up memory
        Glide.with(holder.imageView).clear(holder.imageView)
    }

    fun getSelectedPhotos(): List<LocalPhoto> = currentList.filter { selectedPhotos.contains(it.uri) }

    private class PhotoDiffCallback : DiffUtil.ItemCallback<LocalPhoto>() {
        override fun areItemsTheSame(oldItem: LocalPhoto, newItem: LocalPhoto) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LocalPhoto, newItem: LocalPhoto) =
            oldItem == newItem
    }
}