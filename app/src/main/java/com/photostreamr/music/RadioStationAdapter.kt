package com.photostreamr.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.R


class RadioStationAdapter(
    private val radioManager: RadioManager,
    private val onStationClick: (RadioManager.RadioStation) -> Unit,
    private val onFavoriteClick: (RadioManager.RadioStation) -> Unit,
    private val getFavoriteStatus: (RadioManager.RadioStation) -> Boolean
) : ListAdapter<RadioManager.RadioStation, RadioStationAdapter.StationViewHolder>(StationDiffCallback()) {

    private var loadingStationId: String? = null
    private var loadingStateCallback: ((Boolean) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_radio_station, parent, false)
        return StationViewHolder(
            view,
            onStationClick,
            onFavoriteClick,
            getFavoriteStatus,
            radioManager,
            ::isStationLoading
        )
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    private fun isStationLoading(stationId: String): Boolean {
        return stationId == loadingStationId
    }

    fun setLoadingState(stationId: String?, callback: ((Boolean) -> Unit)? = null) {
        val oldLoadingId = loadingStationId
        loadingStationId = stationId
        loadingStateCallback = callback

        currentList.forEachIndexed { index, station ->
            if (station.id == oldLoadingId || station.id == stationId) {
                notifyItemChanged(index)
            }
        }
    }

    class StationViewHolder(
        itemView: View,
        private val onStationClick: (RadioManager.RadioStation) -> Unit,
        private val onFavoriteClick: (RadioManager.RadioStation) -> Unit,
        private val getFavoriteStatus: (RadioManager.RadioStation) -> Boolean,
        private val radioManager: RadioManager,
        private val isLoading: (String) -> Boolean
    ) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.station_name)
        private val genreTextView: TextView = itemView.findViewById(R.id.station_genre)
        private val favoriteButton: ImageButton = itemView.findViewById(R.id.favorite_button)
        private val logoImageView: ImageView = itemView.findViewById(R.id.station_logo)

        fun bind(station: RadioManager.RadioStation) {
            val isLoadingState = isLoading(station.id)

            nameTextView.text = station.name
            genreTextView.text = station.genre ?: ""

            val loadingIndicator = itemView.findViewById<ProgressBar>(R.id.loading_indicator)
                ?: ProgressBar(itemView.context).also { progress ->
                    progress.id = R.id.loading_indicator
                    progress.visibility = View.GONE
                    (itemView as? ViewGroup)?.addView(progress,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }

            loadingIndicator.visibility = if (isLoadingState) View.VISIBLE else View.GONE

            itemView.isEnabled = !isLoadingState
            itemView.setOnClickListener {
                if (!isLoadingState) {
                    onStationClick(station)
                }
            }

            itemView.contentDescription = itemView.context.getString(
                R.string.station_item_description,
                station.name,
                station.genre ?: ""
            )

            logoImageView.apply {
                alpha = if (isLoadingState) 0.5f else 1.0f
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
                                alpha = if (isLoadingState) 0.5f else 1.0f
                                animate()
                                    .alpha(if (isLoadingState) 0.5f else 1.0f)
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

            val isFavorite = getFavoriteStatus(station)
            favoriteButton.apply {
                isEnabled = !isLoadingState
                alpha = if (isLoadingState) 0.5f else 1.0f
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
                    if (!isLoadingState) {
                        onFavoriteClick(station)
                        setImageResource(
                            if (!isFavorite) R.drawable.ic_favorite_filled
                            else R.drawable.ic_favorite_border
                        )
                        contentDescription = context.getString(
                            if (!isFavorite) R.string.remove_from_favorites_description
                            else R.string.add_to_favorites_description,
                            station.name
                        )
                    }
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