package com.example.screensaver.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.screensaver.R

class RadioStationAdapter(
    private val onStationClick: (RadioManager.RadioStation) -> Unit,
    private val onFavoriteClick: (RadioManager.RadioStation) -> Unit,
    private val getFavoriteStatus: (RadioManager.RadioStation) -> Boolean
) : ListAdapter<RadioManager.RadioStation, RadioStationAdapter.StationViewHolder>(StationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_radio_station, parent, false)
        return StationViewHolder(view, onStationClick, onFavoriteClick, getFavoriteStatus)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class StationViewHolder(
        itemView: View,
        private val onStationClick: (RadioManager.RadioStation) -> Unit,
        private val onFavoriteClick: (RadioManager.RadioStation) -> Unit,
        private val getFavoriteStatus: (RadioManager.RadioStation) -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.station_name)
        private val genreTextView: TextView = itemView.findViewById(R.id.station_genre)
        private val favoriteButton: ImageButton = itemView.findViewById(R.id.favorite_button)

        fun bind(station: RadioManager.RadioStation) {
            nameTextView.text = station.name
            genreTextView.text = station.genre ?: ""
            itemView.setOnClickListener { onStationClick(station) }

            // Update favorite button state
            val isFavorite = getFavoriteStatus(station)
            favoriteButton.setImageResource(
                if (isFavorite) R.drawable.ic_favorite_filled
                else R.drawable.ic_favorite_border
            )
            favoriteButton.setOnClickListener {
                onFavoriteClick(station)
                // Update the button state immediately after click
                favoriteButton.setImageResource(
                    if (!isFavorite) R.drawable.ic_favorite_filled
                    else R.drawable.ic_favorite_border
                )
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