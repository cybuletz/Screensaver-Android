package com.photostreamr.localphotos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.CheckBox
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.R
import android.net.Uri
import android.util.Log
import coil.dispose
import coil.load
import coil.request.CachePolicy
import coil.size.Scale

class LocalPhotoAdapter(
    private val onSelectionChanged: (Boolean) -> Unit,
    private val bitmapMemoryManager: com.photostreamr.ui.BitmapMemoryManager
) : ListAdapter<LocalPhoto, LocalPhotoAdapter.PhotoViewHolder>(PhotoDiffCallback()) {

    private val selectedPhotos = mutableSetOf<Uri>()
    private val thumbnailSize = 300
    private val TAG = "LocalPhotoAdapter"

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

        // COIL REPLACEMENT FOR GLIDE - with proper 2.7.0 API usage
        holder.imageView.load(photo.uri) {
            size(thumbnailSize, thumbnailSize)
            crossfade(true)
            scale(Scale.FILL) // This is the equivalent of centerCrop in Coil
            memoryCachePolicy(CachePolicy.ENABLED)
            diskCachePolicy(CachePolicy.DISABLED) // Don't cache local photos on disk
            listener(
                onSuccess = { _, result ->
                    val drawable = result.drawable
                    // Register bitmap with memory manager for tracking
                    if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
                        bitmapMemoryManager.registerActiveBitmap("thumbnail:${photo.uri.hashCode()}", drawable.bitmap)
                    }
                },
                onError = { _, throwable ->
                    Log.e(TAG, "Error loading thumbnail for ${photo.uri}: ${throwable.toString()}")
                }
            )
        }

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
        try {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                val photo = getItem(position)
                val key = "thumbnail:${photo.uri.hashCode()}"
                bitmapMemoryManager.unregisterActiveBitmap(key)
            }

            // Clear the ImageView properly
            // No need to call any special dispose method, setting null is enough
            holder.imageView.setImageDrawable(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onViewRecycled", e)
        }
    }

    fun getSelectedPhotos(): List<LocalPhoto> = currentList.filter { selectedPhotos.contains(it.uri) }

    private class PhotoDiffCallback : DiffUtil.ItemCallback<LocalPhoto>() {
        override fun areItemsTheSame(oldItem: LocalPhoto, newItem: LocalPhoto) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: LocalPhoto, newItem: LocalPhoto) =
            oldItem == newItem
    }
}