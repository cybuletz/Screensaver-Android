package com.photostreamr.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photostreamr.PhotoRepository
import com.photostreamr.R
import com.photostreamr.models.MediaItem
import com.photostreamr.photos.network.NetworkPhotoManager
import com.photostreamr.photos.network.NetworkResource
import com.photostreamr.photos.network.NetworkResourceAdapter
import com.photostreamr.photos.network.NetworkServer
import com.photostreamr.photos.network.NetworkServerAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
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

        // Initial UI state
        updateSelectedPhotosCount()
        browseBackButton.isEnabled = false

        // Add debug button
        val debugButton = view.findViewById<Button>(R.id.debug_button)
        debugButton?.setOnClickListener {
            showDebugInfo()
        }

        // Log servers on start
        val totalServers = networkPhotoManager.manualConnections.value.size +
                networkPhotoManager.discoveredServers.value.size
        Timber.d("Total servers available: $totalServers (${networkPhotoManager.manualConnections.value.size} manual, ${networkPhotoManager.discoveredServers.value.size} discovered)")

        // Show a toast with the server count
        Toast.makeText(
            requireContext(),
            "$totalServers servers available (${networkPhotoManager.manualConnections.value.size} saved)",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showDebugInfo() {
        val debugInfo = StringBuilder()
        debugInfo.append("Network Discovery State: ${networkPhotoManager.discoveryState.value}\n")
        debugInfo.append("Discovered Servers: ${networkPhotoManager.discoveredServers.value.size}\n")
        debugInfo.append("Manual Servers: ${networkPhotoManager.manualConnections.value.size}\n")
        debugInfo.append("Current Path: ${networkPhotoManager.currentBrowsingPath.value}\n")
        debugInfo.append("Folder Contents: ${networkPhotoManager.folderContents.value.size}\n")

        // Network Info
        val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val wifiManager = context?.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        if (connectivityManager != null) {
            val activeNetwork = connectivityManager.activeNetworkInfo
            debugInfo.append("Network Connected: ${activeNetwork?.isConnected}\n")
            debugInfo.append("Network Type: ${activeNetwork?.typeName}\n")
        }

        if (wifiManager != null) {
            val wifiInfo = wifiManager.connectionInfo
            debugInfo.append("WiFi SSID: ${wifiInfo.ssid}\n")
            debugInfo.append("WiFi IP: ${android.text.format.Formatter.formatIpAddress(wifiInfo.ipAddress)}\n")
            debugInfo.append("WiFi Link Speed: ${wifiInfo.linkSpeed} Mbps\n")

            // Just note that multicast functionality is being used
            debugInfo.append("WiFi Multicast: Used for network discovery\n")
        }

        // Use default alert dialog style
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Network Debug Info")
            .setMessage(debugInfo.toString())
            .setPositiveButton("OK", null)
            .show()
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
                // Update back button state
                browseBackButton.isEnabled = path != null && path.isNotEmpty()
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
            // Navigate to directory
            browseTo(resource)
        } else if (resource.isImage) {
            // Toggle selection for images
            val isCurrentlySelected = selectedResources.contains(resource)
            onResourceSelectionChanged(resource, !isCurrentlySelected)
            // Notify adapter about the change
            resourceAdapter.notifyDataSetChanged()
        }
    }

    private fun onResourceSelectionChanged(resource: NetworkResource, isSelected: Boolean) {
        if (isSelected) {
            selectedResources.add(resource)
        } else {
            selectedResources.remove(resource)
        }

        // Update selected photos count
        updateSelectedPhotosCount()
    }

    private fun browseTo(resource: NetworkResource) {
        progressBar.isVisible = true
        selectedResources.clear()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = networkPhotoManager.browseNetworkPath(resource.server, resource.path)
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
                    getString(R.string.error_browsing_network, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun navigateBack() {
        val server = currentServer ?: return
        val currentPathValue = networkPhotoManager.currentBrowsingPath.value ?: return

        // Get parent path
        val parentPath = if (currentPathValue.contains("/")) {
            currentPathValue.substringBeforeLast("/")
        } else {
            ""
        }

        progressBar.isVisible = true
        selectedResources.clear()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = networkPhotoManager.browseNetworkPath(server, parentPath)
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
                    getString(R.string.error_browsing_network, e.message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showAddServerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_server, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.server_name_edit)
        val addressEditText = dialogView.findViewById<EditText>(R.id.server_address_edit)
        val usernameEditText = dialogView.findViewById<EditText>(R.id.username_edit)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.password_edit)

        // Add helper text to show examples
        addressEditText.hint = "192.168.1.100 or server.local"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_server)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = nameEditText.text.toString().trim()
                val address = addressEditText.text.toString().trim()
                val username = usernameEditText.text.toString().trim().let { if (it.isEmpty()) null else it }
                val password = passwordEditText.text.toString().trim().let { if (it.isEmpty()) null else it }

                if (address.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.invalid_address,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                // Create the server object
                val server = NetworkServer(
                    id = UUID.randomUUID().toString(),
                    name = if (name.isEmpty()) address else name,
                    address = address,
                    username = username,
                    password = password,
                    isManual = true
                )

                // Add server and try to connect
                networkPhotoManager.addManualServer(server)
                Timber.d("Added manual server: ${server.name}, address: ${server.address}")

                // Log that server was added
                Toast.makeText(requireContext(), "Added server: ${server.name}", Toast.LENGTH_SHORT).show()

                // Immediately try to connect to the server
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        progressBar.isVisible = true
                        statusText.text = "Connecting to ${server.name}..."

                        val result = networkPhotoManager.browseNetworkPath(server)
                        progressBar.isVisible = false

                        if (result.isFailure) {
                            statusText.text = "Failed to connect to ${server.name}"
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.error_connecting_to_server, result.exceptionOrNull()?.message),
                                Toast.LENGTH_SHORT
                            ).show()
                            Timber.e("Failed to connect: ${result.exceptionOrNull()?.message}")
                        } else {
                            statusText.text = "Connected to ${server.name}"
                            Toast.makeText(
                                requireContext(),
                                "Connected to ${server.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } catch (e: Exception) {
                        progressBar.isVisible = false
                        statusText.text = "Failed to connect to ${server.name}"
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.error_connecting_to_server, e.message),
                            Toast.LENGTH_SHORT
                        ).show()
                        Timber.e(e, "Error connecting to server")
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveServer(server: NetworkServer) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove Server")
            .setMessage("Remove server ${server.name}?")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                networkPhotoManager.removeManualServer(server)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addSelectedPhotosToRepository() {
        if (selectedResources.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_photos_selected, Toast.LENGTH_SHORT).show()
            return
        }

        // Show progress indicator
        progressBar.isVisible = true
        statusText.text = getString(R.string.adding_network_photos)

        // Show progress dialog
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.adding_photos)
            .setView(layoutInflater.inflate(R.layout.dialog_progress, null))
            .setCancelable(false)
            .show()

        // Start background work
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mediaItems = mutableListOf<MediaItem>()
                var processedCount = 0

                // Process each selected resource
                for (resource in selectedResources) {
                    processedCount++

                    // Update progress on main thread
                    withContext(Dispatchers.Main) {
                        val progressView = progressDialog.findViewById<TextView>(R.id.statusText)
                        progressView?.text = getString(R.string.processing_photo, processedCount, selectedResources.size)
                    }

                    Log.d(TAG, "Processing network photo: ${resource.name}")

                    // Download and cache photo
                    val uri = networkPhotoManager.getCachedPhotoUri(resource)
                    if (uri != null) {
                        val uriString = uri.toString()

                        // Create media item with unique ID
                        val mediaItem = MediaItem(
                            id = "${resource.server.id}_${resource.path.hashCode()}",
                            albumId = "network_" + resource.server.id,
                            baseUrl = uriString,
                            mimeType = getMimeType(resource.name),
                            width = 0,
                            height = 0,
                            description = resource.name,
                            createdAt = System.currentTimeMillis(),
                            loadState = MediaItem.LoadState.IDLE
                        )

                        mediaItems.add(mediaItem)
                        Log.d(TAG, "Created MediaItem for network photo: $uriString")
                    } else {
                        Log.e(TAG, "Failed to get URI for: ${resource.name}")
                    }
                }

                // Switch to main thread to update UI
                withContext(Dispatchers.Main) {
                    try {
                        progressDialog.dismiss()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dismissing dialog", e)
                    }

                    if (mediaItems.isEmpty()) {
                        progressBar.isVisible = false
                        statusText.text = getString(R.string.no_photos_added)
                        Toast.makeText(
                            requireContext(),
                            R.string.failed_to_add_photos,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }

                    // Add to PhotoRepository using the correct mode from your implementation
                    photoRepository.addPhotos(mediaItems, PhotoRepository.PhotoAddMode.MERGE)

                    // Update UI
                    progressBar.isVisible = false
                    statusText.text = getString(R.string.network_photos_added, mediaItems.size)

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.added_network_photos, mediaItems.size),
                        Toast.LENGTH_SHORT
                    ).show()

                    // Update selected photos in parent fragment
                    val parentFragment = parentFragment
                    if (parentFragment is PhotoSourcesPreferencesFragment) {
                        parentFragment.onNetworkPhotosAdded(mediaItems.size)
                    }

                    // Clear selection
                    selectedResources.clear()
                    resourceAdapter.submitList(networkPhotoManager.folderContents.value)
                    updateSelectedPhotosCount()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding network photos", e)

                withContext(Dispatchers.Main) {
                    try {
                        progressDialog.dismiss()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error dismissing dialog", ex)
                    }

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

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            fileName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> "image/jpeg" // Default to JPEG
        }
    }

    private fun updateSelectedPhotosCount() {
        val count = selectedResources.size
        addSelectedPhotosButton.apply {
            text = if (count > 0) {
                getString(R.string.add_selected_photos) + " ($count)"
            } else {
                getString(R.string.add_selected_photos)
            }
            isEnabled = count > 0
            alpha = if (count > 0) 1.0f else 0.5f
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        networkPhotoManager.stopDiscovery()
    }
}