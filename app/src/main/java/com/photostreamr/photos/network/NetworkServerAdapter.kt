package com.photostreamr.photos.network

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.photostreamr.R

class NetworkServerAdapter(
    private val onServerClick: (NetworkServer) -> Unit,
    private val onRemoveClick: (NetworkServer) -> Unit
) : RecyclerView.Adapter<NetworkServerAdapter.ServerViewHolder>() {

    private val TAG = "NetworkServerAdapter"
    private var servers: List<NetworkServer> = emptyList()

    fun submitList(newList: List<NetworkServer>?) {
        Log.d(TAG, "submitList called with ${newList?.size ?: 0} servers")
        servers = newList ?: emptyList()
        notifyDataSetChanged()
        Log.d(TAG, "After submitList, getItemCount()=${getItemCount()}")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        Log.d(TAG, "onCreateViewHolder called")
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_network_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        Log.d(TAG, "onBindViewHolder called for position $position")
        val server = servers[position]
        holder.bind(server)
    }

    override fun getItemCount(): Int {
        val count = servers.size
        Log.d(TAG, "getItemCount called, returning $count")
        return count
    }

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.server_name)
        private val addressTextView: TextView = itemView.findViewById(R.id.server_address)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_server_button)

        fun bind(server: NetworkServer) {
            Log.d(TAG, "Binding server: ${server.name}")
            nameTextView.text = server.name
            addressTextView.text = server.address

            itemView.setOnClickListener {
                onServerClick(server)
            }

            removeButton.setOnClickListener {
                onRemoveClick(server)
            }

            // Show/hide remove button based on server type
            removeButton.visibility = if (server.isManual) View.VISIBLE else View.GONE
        }
    }
}