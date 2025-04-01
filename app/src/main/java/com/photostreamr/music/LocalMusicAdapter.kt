package com.photostreamr.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.R
import java.util.concurrent.TimeUnit

class LocalMusicAdapter(
    private val onTrackClick: (LocalMusicManager.LocalTrack) -> Unit
) : ListAdapter<LocalMusicManager.LocalTrack, LocalMusicAdapter.TrackViewHolder>(TrackDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.item_local_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = getItem(position)
        holder.bind(track)
    }

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.track_title)
        private val artistText: TextView = itemView.findViewById(R.id.artist_name)
        private val durationText: TextView = itemView.findViewById(R.id.track_duration)

        fun bind(track: LocalMusicManager.LocalTrack) {
            titleText.text = track.title
            artistText.text = track.artist

            // Format duration
            val minutes = TimeUnit.MILLISECONDS.toMinutes(track.duration)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(track.duration) % 60
            durationText.text = String.format("%d:%02d", minutes, seconds)

            // Set click listener
            itemView.setOnClickListener {
                onTrackClick(track)
            }
        }
    }

    private class TrackDiffCallback : DiffUtil.ItemCallback<LocalMusicManager.LocalTrack>() {
        override fun areItemsTheSame(
            oldItem: LocalMusicManager.LocalTrack,
            newItem: LocalMusicManager.LocalTrack
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: LocalMusicManager.LocalTrack,
            newItem: LocalMusicManager.LocalTrack
        ): Boolean {
            return oldItem == newItem
        }
    }
}