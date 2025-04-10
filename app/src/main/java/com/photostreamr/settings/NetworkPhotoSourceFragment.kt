package com.photostreamr.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photostreamr.R
import com.photostreamr.photos.PhotoManagerViewModel
import com.photostreamr.PhotoRepository
import com.photostreamr.models.MediaItem
import com.photostreamr.photos.PhotoSourceType
import com.photostreamr.photos.network.NetworkPhotoManager
import com.photostreamr.photos.network.NetworkResource
import com.photostreamr.photos.network.NetworkServer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class NetworkPhotoSourceFragment : Fragment() {

    companion object {
        private const val TAG = "NetworkPhotoSourceFrag"
    }

    @Inject
    lateinit var networkPhotoManager: NetworkPhotoManager

    @Inject
    lateinit var photoRepository: PhotoRepository

    private val photoManagerViewModel: PhotoManagerViewModel by viewModels()

    // UI components
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var serversRecyclerView: RecyclerView
    private lateinit var browseRecyclerView: RecyclerView
    private lateinit var addServerButton: Button
    private lateinit var serverAdapter: NetworkServerAdapter
    private lateinit var resourceAdapter: NetworkResourceAdapter
    private lateinit var selectedPathText: TextView
    private lateinit var browseBackButton: Button
    private lateinit var addSelectedPhotosButton: Button

    private var currentServer: NetworkServer? = null
    private var currentPath = ""
    private val selectedResources = mutableListOf<NetworkResource>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_network_photos, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI components
        progressBar = view.findViewById(R.id.progress_bar)
        statusText = view.findViewById(R.id.status_text)
        serversRecyclerView = view.findViewById(R.id.servers_recycler_view)
        browseRecyclerView = view.findViewById(R.id.browse_recycler_view)
        addServerButton = view.findViewById(R.id.add_server_button)
        selectedPathText = view.findViewById(R.id.selected_path_text)
        browseBackButton = view.findViewById(R.id.browse_back_button)
        addSelectedPhotosButton = view.findViewById(R.id.add_selected_photos_button)

        // Configure recycler views
        serversRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        serversRecyclerView.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        browseRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        browseRecyclerView.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        // Initialize adapters
        serverAdapter = NetworkServerAdapter(
            onServerClick = { server -> onServerSelected(server) },
            onRemoveClick = { server -> confirmRemoveServer(server) }
        )
        serversRecyclerView.adapter = serverAdapter

        resourceAdapter = NetworkResourceAdapter(
            onResourceClick = { resource -> onResourceSelected(resource) },
            onResourceSelect = { resource, isSelected -> onResourceSelectionChanged(resource, isSelected) }
        )
        browseRecyclerView.adapter = resourceAdapter

        // Set up listeners
        addServerButton.setOnClickListener { showAddServerDialog() }
        browseBackButton.setOnClickListener { navigateBack() }
        addSelectedPhotosButton.setOnClickListener { addSelectedPhotosToRepository() }

        // Observe network photo manager state
        collectFlows()

        // Start network discovery
        networkPhotoManager.initialize()
        networkPhotoManager.startDiscovery()
    }

    private fun collectFlows() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Collect discovery state
            networkPhotoManager.discoveryState.collectLatest { state ->
                when (state) {
                    is NetworkPhotoManager.DiscoveryState.Searching -> {
                        progressBar.isVisible = true
                        statusText.text = getString(R.string.searching_for_servers)
                    }
                    is NetworkPhotoManager.DiscoveryState.Error -> {
                        progressBar.isVisible = false
                        statusText.text = state.message
                    }
                    else -> {
                        progressBar.isVisible = false
                        statusText.text = ""
                    }
                }
            }
        }

        // Collect discovered servers
        viewLifecycleOwner.lifecycleScope.launch {
            networkPhotoManager.discoveredServers.collectLatest { servers ->
                updateServerList()
            }
        }

        // Collect manual servers
        viewLifecycleOwner.lifecycleScope.launch {
            networkPhotoManager.manualConnections.collectLatest { servers ->
                updateServerList()
            }
        }

        // Collect current browsing path
        viewLifecycleOwner.lifecycleScope.launch {
            networkPhotoManager.currentBrowsingPath.collectLatest { path ->
                selectedPathText.text = path ?: getString(R.string.no_folder_selected)
            }
        }

        // Collect folder contents
        viewLifecycleOwner.lifecycleScope.launch {
            networkPhotoManager.folderContents.collectLatest { resources ->
                resourceAdapter.submitList(resources)

                // Update selected photos count in button text
                updateSelectedPhotosCount()
            }
        }
    }

    private fun updateServerList() {
        val allServers = mutableListOf<NetworkServer>().apply {
            addAll(networkPhotoManager.discoveredServers.value)
            addAll(networkPhotoManager.manualConnections.value)
        }
        serverAdapter.submitList(allServers)

        // Update status text if no servers
        if (allServers.isEmpty()) {
            statusText.text = getString(R.string.no_servers_found)
        } else if (networkPhotoManager.discoveryState.value !is NetworkPhotoManager.DiscoveryState.Searching) {
            statusText.text = getString(R.string.select_server_to_browse)
        }
    }

    private fun onServerSelected(server: NetworkServer) {
        currentServer = server
        currentPath = ""
        selectedResources.clear()

        progressBar.isVisible = true

        // Start browsing the server
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = networkPhotoManager.browseNetworkPath(server)
                progressBar.isVisible = false

                if (result.isFailure) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_browsing_network, result.exceptionOrNull()?.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                progressBar.isVisible = false
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_connecting_to_server, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onResourceSelected(resource: NetworkResource) {
        if (resource.isDirectory) {
            // Navigate into directory
            progressBar.isVisible = true
            currentPath = resource.path

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = networkPhotoManager.browseNetworkPath(resource.server, resource.path)
                    progressBar.isVisible = false

                    if (result.isFailure) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.error_browsing_folder, result.exceptionOrNull()?.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    progressBar.isVisible = false
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_browsing_folder, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            // Toggle selection for image files
            val isSelected = !selectedResources.contains(resource)
            onResourceSelectionChanged(resource, isSelected)
        }
    }

    private fun onResourceSelectionChanged(resource: NetworkResource, isSelected: Boolean) {
        if (isSelected) {
            selectedResources.add(resource)
        } else {
            selectedResources.remove(resource)
        }

        // Update the adapter to reflect selection state
        val position = resourceAdapter.resources.indexOf(resource)
        if (position != -1) {
            resourceAdapter.notifyItemChanged(position)
        }

        // Update selected photos count
        updateSelectedPhotosCount()
    }

    private fun updateSelectedPhotosCount() {
        val selectedCount = selectedResources.size
        addSelectedPhotosButton.text = getString(R.string.add_selected_photos, selectedCount)
        addSelectedPhotosButton.isEnabled = selectedCount > 0
    }

    private fun navigateBack() {
        currentServer?.let { server ->
            // Extract parent path
            val currentPath = networkPhotoManager.currentBrowsingPath.value ?: return
            val parentPath = when (server.protocol) {
                NetworkPhotoManager.PROTOCOL_SMB -> {
                    val path = currentPath.substringAfter("smb://")
                        .substringAfter("/", "")
                    val parentDir = path.substringBeforeLast("/", "")
                    parentDir
                }
                NetworkPhotoManager.PROTOCOL_WEBDAV -> {
                    val path = currentPath.substringAfter("://")
                        .substringAfter("/", "")
                    val parentDir = path.substringBeforeLast("/", "")
                    parentDir
                }
                else -> ""
            }

            // Browse to parent directory
            progressBar.isVisible = true
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = networkPhotoManager.browseNetworkPath(server, parentPath)
                    progressBar.isVisible = false

                    if (result.isFailure) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.error_browsing_folder, result.exceptionOrNull()?.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    progressBar.isVisible = false
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_browsing_folder, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun addSelectedPhotosToRepository() {
        if (selectedResources.isEmpty()) {
            return
        }

        progressBar.isVisible = true
        statusText.text = getString(R.string.adding_network_photos)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val mediaItems = mutableListOf<MediaItem>()

                // Download and cache selected photos
                for (resource in selectedResources) {
                    val uri = networkPhotoManager.getCachedPhotoUri(resource)
                    if (uri != null) {
                        val uriString = uri.toString()

                        // Create media item
                        val mediaItem = MediaItem(
                            id = uriString,
                            albumId = "network_" + resource.server.id,
                            baseUrl = uriString,
                            mimeType = "image/*",
                            width = 0,
                            height = 0,
                            description = resource.name,
                            createdAt = System.currentTimeMillis(),
                            loadState = MediaItem.LoadState.IDLE
                        )

                        mediaItems.add(mediaItem)
                    }
                }

                // Add to PhotoRepository
                photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.APPEND)

                // Update app data
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.network_photos_added, mediaItems.size),
                        Toast.LENGTH_SHORT
                    ).show()

                    // Update selected photos in parent fragment
                    val parentFragment = parentFragment
                    if (parentFragment is PhotoSourcesPreferencesFragment) {
                        parentFragment.onNetworkPhotosAdded(mediaItems.size)
                    }

                    progressBar.isVisible = false
                    statusText.text = getString(R.string.network_photos_added, mediaItems.size)

                    // Clear selection
                    selectedResources.clear()
                    resourceAdapter.notifyDataSetChanged()
                    updateSelectedPhotosCount()
                }

            } catch (e: Exception) {
                Timber.e(e, "Error adding network photos")
                withContext(Dispatchers.Main) {
                    progressBar.isVisible = false
                    statusText.text = getString(R.string.error_adding_photos)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_adding_photos_detail, e.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showAddServerDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_network_server, null)

        val nameEditText = dialogView.findViewById<EditText>(R.id.server_name_input)
        val hostEditText = dialogView.findViewById<EditText>(R.id.server_host_input)
        val portEditText = dialogView.findViewById<EditText>(R.id.server_port_input)
        val protocolGroup = dialogView.findViewById<RadioGroup>(R.id.protocol_radio_group)
        val usernameEditText = dialogView.findViewById<EditText>(R.id.username_input)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.password_input)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_network_server)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = nameEditText.text.toString().trim()
                val host = hostEditText.text.toString().trim()
                val portStr = portEditText.text.toString().trim()
                val protocol = when (protocolGroup.checkedRadioButtonId) {
                    R.id.radio_smb -> NetworkPhotoManager.PROTOCOL_SMB
                    R.id.radio_webdav -> NetworkPhotoManager.PROTOCOL_WEBDAV
                    else -> NetworkPhotoManager.PROTOCOL_SMB
                }
                val username = usernameEditText.text.toString().trim().ifEmpty { null }
                val password = passwordEditText.text.toString().trim().ifEmpty { null }

                if (name.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.please_fill_all_fields,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                try {
                    val port = portStr.toInt()
                    if (port <= 0 || port > 65535) {
                        throw NumberFormatException("Port must be between 1 and 65535")
                    }

                    // Add the server
                    val server = networkPhotoManager.addManualServer(
                        name = name,
                        host = host,
                        port = port,
                        protocol = protocol,
                        username = username,
                        password = password
                    )

                    // Select the newly added server
                    onServerSelected(server)

                } catch (e: NumberFormatException) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.invalid_port_number),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveServer(server: NetworkServer) {
        // Only show confirmation for manual servers
        if (server.type == NetworkServer.Type.MANUAL) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.remove_server)
                .setMessage(getString(R.string.confirm_remove_server, server.name))
                .setPositiveButton(R.string.remove) { _, _ ->
                    networkPhotoManager.removeManualServer(server.id)

                    // If this was the current server, clear the view
                    if (currentServer?.id == server.id) {
                        currentServer = null
                        resourceAdapter.submitList(emptyList())
                        selectedPathText.text = getString(R.string.no_folder_selected)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        networkPhotoManager.stopDiscovery()
    }
}

// Adapters for the RecyclerViews
class NetworkServerAdapter(
    private val onServerClick: (NetworkServer) -> Unit,
    private val onRemoveClick: (NetworkServer) -> Unit
) : RecyclerView.Adapter<NetworkServerAdapter.ServerViewHolder>() {

    private var servers = listOf<NetworkServer>()

    fun submitList(newList: List<NetworkServer>) {
        servers = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_server, parent, false)
        return ServerViewHolder(view)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(servers[position])
    }

    override fun getItemCount(): Int = servers.size

    inner class ServerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.server_name)
        private val typeText: TextView = itemView.findViewById(R.id.server_type)
        private val removeButton: Button = itemView.findViewById(R.id.remove_server_button)

        fun bind(server: NetworkServer) {
            nameText.text = server.name
            typeText.text = server.displayName

            // Only show remove button for manual servers
            removeButton.isVisible = server.type == NetworkServer.Type.MANUAL

            itemView.setOnClickListener { onServerClick(server) }
            removeButton.setOnClickListener { onRemoveClick(server) }
        }
    }
}

class NetworkResourceAdapter(
    private val onResourceClick: (NetworkResource) -> Unit,
    private val onResourceSelect: (NetworkResource, Boolean) -> Unit
) : RecyclerView.Adapter<NetworkResourceAdapter.ResourceViewHolder>() {

    private var resources = listOf<NetworkResource>()
    private val selectedResources = mutableSetOf<String>() // Store selected resource IDs

    fun submitList(newList: List<NetworkResource>) {
        resources = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_network_resource, parent, false)
        return ResourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResourceViewHolder, position: Int) {
        holder.bind(resources[position])
    }

    override fun getItemCount(): Int = resources.size

    inner class ResourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.resource_name)
        private val typeIcon: View = itemView.findViewById(R.id.resource_type_icon)
        private val selectCheckbox: View = itemView.findViewById(R.id.resource_checkbox)

        fun bind(resource: NetworkResource) {
            nameText.text = resource.name

            // Set icon based on resource type
            typeIcon.setBackgroundResource(
                if (resource.isDirectory) R.drawable.ic_folder
                else R.drawable.ic_photo
            )

            // Only show checkbox for images, not directories
            selectCheckbox.isVisible = !resource.isDirectory

            // Set checkbox state
            selectCheckbox.isSelected = selectedResources.contains(resource.id)

            itemView.setOnClickListener { onResourceClick(resource) }
            selectCheckbox.setOnClickListener {
                val newState = !selectedResources.contains(resource.id)
                if (newState) {
                    selectedResources.add(resource.id)
                } else {
                    selectedResources.remove(resource.id)
                }
                selectCheckbox.isSelected = newState
                onResourceSelect(resource, newState)
            }
        }
    }
}