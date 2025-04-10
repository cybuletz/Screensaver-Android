package com.photostreamr.photos.network

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.R

class NetworkResourceAdapter(
    private val onResourceClick: (NetworkResource) -> Unit,
    private val onResourceSelect: (NetworkResource, Boolean) -> Unit
) : RecyclerView.Adapter<NetworkResourceAdapter.ResourceViewHolder>() {

    private var resources: List<NetworkResource> = emptyList()
    private val selectedResources = mutableSetOf<NetworkResource>()

    fun submitList(newList: List<NetworkResource>) {
        resources = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResourceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_network_resource, parent, false)
        return ResourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResourceViewHolder, position: Int) {
        val resource = resources[position]
        holder.bind(resource)
    }

    override fun getItemCount(): Int = resources.size

    inner class ResourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.resource_name)
        private val typeImageView: ImageView = itemView.findViewById(R.id.resource_type_icon)
        private val selectCheckbox: CheckBox = itemView.findViewById(R.id.resource_select_checkbox)

        fun bind(resource: NetworkResource) {
            nameTextView.text = resource.name

            // Set icon based on resource type
            val iconResId = when {
                resource.isDirectory -> R.drawable.ic_folder
                resource.isImage -> R.drawable.ic_image
                else -> R.drawable.ic_file
            }
            typeImageView.setImageResource(iconResId)

            // Show checkbox only for image files
            selectCheckbox.visibility = if (resource.isImage) View.VISIBLE else View.GONE

            // Set checkbox state
            selectCheckbox.isChecked = selectedResources.contains(resource)

            // Set click listeners
            itemView.setOnClickListener {
                onResourceClick(resource)
            }

            selectCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedResources.add(resource)
                } else {
                    selectedResources.remove(resource)
                }
                onResourceSelect(resource, isChecked)
            }
        }
    }

    fun getSelectedResources(): Set<NetworkResource> {
        return selectedResources.toSet()
    }

    fun clearSelection() {
        selectedResources.clear()
        notifyDataSetChanged()
    }
}