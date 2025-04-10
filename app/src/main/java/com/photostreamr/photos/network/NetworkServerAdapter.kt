package com.photostreamr.photos.network

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

    private var servers: List<NetworkServer> = emptyList()

    fun submitList(newList: List<NetworkServer>) {
        servers = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_network_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        holder.bind(server)
    }

    override fun getItemCount(): Int = servers.size

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.server_name)
        private val addressTextView: TextView = itemView.findViewById(R.id.server_address)
        private val removeButton: ImageButton = itemView.findViewById(R.id.remove_server_button)

        fun bind(server: NetworkServer) {
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