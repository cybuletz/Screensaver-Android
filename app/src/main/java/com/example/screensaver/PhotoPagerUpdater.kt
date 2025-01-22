package com.example.screensaver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class PhotoPagerAdapter : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {
    private val photos = mutableListOf<String>()

    class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.photoImageView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photoUrl = photos[position]
        Glide.with(holder.imageView)
            .load(photoUrl)
            .centerCrop()
            .into(holder.imageView)
    }

    override fun getItemCount() = photos.size

    fun addPhotos(newPhotos: List<String>) {
        val startPos = photos.size
        photos.addAll(newPhotos)
        notifyItemRangeInserted(startPos, newPhotos.size)
    }
}