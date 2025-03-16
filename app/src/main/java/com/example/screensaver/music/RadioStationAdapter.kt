package com.example.screensaver.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.screensaver.R


class RadioStationAdapter(
    private val radioManager: RadioManager, // Add this parameter
    private val onStationClick: (RadioManager.RadioStation) -> Unit,
    private val onFavoriteClick: (RadioManager.RadioStation) -> Unit,
    private val getFavoriteStatus: (RadioManager.RadioStation) -> Boolean
) : ListAdapter<RadioManager.RadioStation, RadioStationAdapter.StationViewHolder>(StationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_radio_station, parent, false)
        return StationViewHolder(view, onStationClick, onFavoriteClick, getFavoriteStatus, radioManager)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class StationViewHolder(
        itemView: View,
        private val onStationClick: (RadioManager.RadioStation) -> Unit,
        private val onFavoriteClick: (RadioManager.RadioStation) -> Unit,
        private val getFavoriteStatus: (RadioManager.RadioStation) -> Boolean,
        private val radioManager: RadioManager // Add RadioManager parameter
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.station_name)
        private val genreTextView: TextView = itemView.findViewById(R.id.station_genre)
        private val favoriteButton: ImageButton = itemView.findViewById(R.id.favorite_button)
        private val logoImageView: ImageView = itemView.findViewById(R.id.station_logo)

        fun bind(station: RadioManager.RadioStation) {
            // Set station name and genre
            nameTextView.text = station.name
            genreTextView.text = station.genre ?: ""

            // Set click listener for the whole item
            itemView.setOnClickListener { onStationClick(station) }
            itemView.contentDescription = itemView.context.getString(
                R.string.station_item_description,
                station.name,
                station.genre ?: ""
            )

            // Load station logo with proper accessibility
            logoImageView.apply {
                setImageResource(R.drawable.ic_radio_default)
                contentDescription = itemView.context.getString(
                    R.string.station_logo_description,
                    station.name
                )

                if (!station.favicon.isNullOrEmpty()) {
                    radioManager.loadStationLogo(station) { bitmap ->
                        if (bitmap != null) {
                            post {
                                setImageBitmap(bitmap)
                                // Optionally add a fade-in animation
                                alpha = 0f
                                animate()
                                    .alpha(1f)
                                    .setDuration(200)
                                    .start()
                            }
                        } else {
                            post {
                                setImageResource(R.drawable.ic_radio_default)
                            }
                        }
                    }
                }
            }

            // Update favorite button state with accessibility
            val isFavorite = getFavoriteStatus(station)
            favoriteButton.apply {
                setImageResource(
                    if (isFavorite) R.drawable.ic_favorite_filled
                    else R.drawable.ic_favorite_border
                )

                contentDescription = context.getString(
                    if (isFavorite) R.string.remove_from_favorites_description
                    else R.string.add_to_favorites_description,
                    station.name
                )

                setOnClickListener {
                    onFavoriteClick(station)
                    // Update button immediately for better UX
                    setImageResource(
                        if (!isFavorite) R.drawable.ic_favorite_filled
                        else R.drawable.ic_favorite_border
                    )
                    // Update accessibility description
                    contentDescription = context.getString(
                        if (!isFavorite) R.string.remove_from_favorites_description
                        else R.string.add_to_favorites_description,
                        station.name
                    )
                }
            }
        }
    }

    private class StationDiffCallback : DiffUtil.ItemCallback<RadioManager.RadioStation>() {
        override fun areItemsTheSame(
            oldItem: RadioManager.RadioStation,
            newItem: RadioManager.RadioStation
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: RadioManager.RadioStation,
            newItem: RadioManager.RadioStation
        ): Boolean {
            return oldItem == newItem
        }
    }
}