package com.photostreamr.photos.network

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.photostreamr.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.MalformedURLException
import javax.inject.Inject
import javax.inject.Singleton
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.SmbFile
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import java.io.IOException
import java.util.concurrent.TimeUnit

@Singleton
class NetworkPhotoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository
) {
    companion object {
        private const val TAG = "NetworkPhotoManager"

        // Service discovery constants
        private const val SERVICE_TYPE = "_smb._tcp."

        // Protocol identifiers
        const val PROTOCOL_SMB = "smb"
        const val PROTOCOL_WEBDAV = "webdav"

        // Source identifier
        const val SOURCE_NETWORK_PHOTOS = "network_photos"
    }

    // Network device discovery
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // State management
    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState = _discoveryState.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<NetworkServer>>(emptyList())
    val discoveredServers = _discoveredServers.asStateFlow()

    // Manual connections
    private val _manualConnections = MutableStateFlow<List<NetworkServer>>(emptyList())
    val manualConnections = _manualConnections.asStateFlow()

    // Currently browsing server
    private val _currentBrowsingPath = MutableStateFlow<String?>(null)
    val currentBrowsingPath = _currentBrowsingPath.asStateFlow()

    // Current folder contents
    private val _folderContents = MutableStateFlow<List<NetworkResource>>(emptyList())
    val folderContents = _folderContents.asStateFlow()

    // Cached photo URIs from network shares
    private val cachedNetworkPhotos = ConcurrentHashMap<String, Uri>()

    // SMB client context
    private val cifsContext: CIFSContext by lazy {
        SingletonContext.getInstance()
    }

    // OkHttp client for WebDAV
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // Initialize the network photo manager
    fun initialize() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    // Start network discovery
    fun startDiscovery() {
        if (discoveryListener != null) {
            stopDiscovery()
        }

        _discoveryState.value = DiscoveryState.Searching

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("Service discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Timber.d("Service found: ${serviceInfo.serviceName}")
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Timber.e("Failed to resolve service: ${serviceInfo.serviceName}, error: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val server = NetworkServer(
                            id = serviceInfo.serviceName + "_" + serviceInfo.host.hostAddress,
                            name = serviceInfo.serviceName,
                            host = serviceInfo.host.hostAddress ?: "",
                            port = serviceInfo.port,
                            protocol = PROTOCOL_SMB, // Assuming SMB for discovered services
                            type = NetworkServer.Type.DISCOVERED,
                            isAvailable = true
                        )

                        // Add to the list if not already present
                        val currentList = _discoveredServers.value.toMutableList()
                        if (currentList.none { it.id == server.id }) {
                            currentList.add(server)
                            _discoveredServers.value = currentList
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.d("Service lost: ${serviceInfo.serviceName}")
                val currentList = _discoveredServers.value.toMutableList()
                val serverToRemove = currentList.find {
                    it.name == serviceInfo.serviceName && it.type == NetworkServer.Type.DISCOVERED
                }
                if (serverToRemove != null) {
                    currentList.remove(serverToRemove)
                    _discoveredServers.value = currentList
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("Service discovery stopped")
                _discoveryState.value = DiscoveryState.Idle
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery start failed: $errorCode")
                _discoveryState.value = DiscoveryState.Error("Failed to start discovery: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery stop failed: $errorCode")
                _discoveryState.value = DiscoveryState.Error("Failed to stop discovery: error $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            _discoveryState.value = DiscoveryState.Error("Failed to start discovery: ${e.message}")
        }
    }

    // Stop network discovery
    fun stopDiscovery() {
        try {
            discoveryListener?.let {
                nsdManager?.stopServiceDiscovery(it)
            }
            discoveryListener = null
            _discoveryState.value = DiscoveryState.Idle
        } catch (e: Exception) {
            _discoveryState.value = DiscoveryState.Error("Failed to stop discovery: ${e.message}")
        }
    }

    // Add manual server connection
    fun addManualServer(
        name: String,
        host: String,
        port: Int,
        protocol: String,
        username: String? = null,
        password: String? = null
    ): NetworkServer {
        val server = NetworkServer(
            id = "$name-$host-$port-$protocol",
            name = name,
            host = host,
            port = port,
            protocol = protocol,
            username = username,
            password = password,
            type = NetworkServer.Type.MANUAL,
            isAvailable = true
        )

        val currentList = _manualConnections.value.toMutableList()
        // Replace if exists with same ID, otherwise add
        val existingIndex = currentList.indexOfFirst { it.id == server.id }
        if (existingIndex >= 0) {
            currentList[existingIndex] = server
        } else {
            currentList.add(server)
        }

        _manualConnections.value = currentList
        return server
    }

    // Remove manual server
    fun removeManualServer(serverId: String) {
        val currentList = _manualConnections.value.toMutableList()
        currentList.removeAll { it.id == serverId }
        _manualConnections.value = currentList
    }

    // Browse a network path
    suspend fun browseNetworkPath(server: NetworkServer, path: String = ""): Result<List<NetworkResource>> {
        return withContext(Dispatchers.IO) {
            try {
                val fullPath = constructFullPath(server, path)
                _currentBrowsingPath.value = fullPath

                val resources = when (server.protocol) {
                    PROTOCOL_SMB -> browseSmbPath(server, path)
                    PROTOCOL_WEBDAV -> browseWebdavPath(server, path)
                    else -> emptyList()
                }

                _folderContents.value = resources
                Result.success(resources)
            } catch (e: Exception) {
                Timber.e(e, "Error browsing network path $path on server ${server.name}")
                Result.failure(e)
            }
        }
    }

    // Construct full network path
    private fun constructFullPath(server: NetworkServer, path: String): String {
        return when (server.protocol) {
            PROTOCOL_SMB -> {
                val credentials = if (server.username != null && server.password != null) {
                    "${server.username}:${server.password}@"
                } else {
                    ""
                }
                "smb://$credentials${server.host}:${server.port}/$path"
            }
            PROTOCOL_WEBDAV -> {
                "http://${server.host}:${server.port}/${path.trim('/')}"
            }
            else -> ""
        }
    }

    // Browse SMB path
    private fun browseSmbPath(server: NetworkServer, path: String): List<NetworkResource> {
        val smbUrl = constructFullPath(server, path)
        val smbFile = SmbFile(smbUrl, cifsContext)

        return smbFile.listFiles().mapNotNull { file ->
            val isDirectory = file.isDirectory
            val name = file.name.trimEnd('/')

            // Only include directories and image files
            if (isDirectory || isImageFile(name)) {
                NetworkResource(
                    id = file.path,
                    name = name,
                    path = file.path,
                    isDirectory = isDirectory,
                    server = server
                )
            } else {
                null
            }
        }
    }

    // Browse WebDAV path
    private fun browseWebdavPath(server: NetworkServer, path: String): List<NetworkResource> {
        val webdavUrl = constructFullPath(server, path)
        val request = Request.Builder().url(webdavUrl)

        // Add authentication if provided
        if (server.username != null && server.password != null) {
            val credentials = Credentials.basic(server.username, server.password)
            request.header("Authorization", credentials)
        }

        // Use PROPFIND method for WebDAV directory listing
        request.method("PROPFIND", null)
        request.header("Depth", "1")

        val response = okHttpClient.newCall(request.build()).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to list directory: ${response.code}")
        }

        // Simple parsing of the WebDAV response
        val responseBody = response.body?.string() ?: ""

        // Very basic WebDAV XML parsing - in a production app,
        // you'd use a proper XML parser here
        val resources = mutableListOf<NetworkResource>()

        // Extract href elements
        val hrefPattern = "<d:href>(.*?)</d:href>".toRegex()
        val typePattern = "<d:resourcetype><d:collection /></d:resourcetype>".toRegex()

        val matches = hrefPattern.findAll(responseBody)
        for (match in matches) {
            val href = match.groupValues[1]
            if (href == webdavUrl) continue // Skip the directory itself

            val name = href.trimEnd('/').substringAfterLast('/')
            val isDirectory = typePattern.find(responseBody, match.range.first) != null

            // Only include directories and image files
            if (isDirectory || isImageFile(name)) {
                resources.add(NetworkResource(
                    id = href,
                    name = name,
                    path = href,
                    isDirectory = isDirectory,
                    server = server
                ))
            }
        }

        return resources
    }

    // Check if file is an image based on extension
    private fun isImageFile(filename: String): Boolean {
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in imageExtensions
    }

    // Download and cache a network photo
    suspend fun getCachedPhotoUri(resource: NetworkResource): Uri? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheKey = resource.path

                // Check if already cached
                cachedNetworkPhotos[cacheKey]?.let { return@withContext it }

                // Create cache file
                val cacheDir = File(context.cacheDir, "network_photos")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                val cachedFile = File(cacheDir, "${resource.id.hashCode()}_${resource.name}")

                // Download file based on protocol
                when (resource.server.protocol) {
                    PROTOCOL_SMB -> {
                        val smbFile = SmbFile(resource.path, cifsContext)
                        smbFile.inputStream.use { input ->
                            cachedFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    PROTOCOL_WEBDAV -> {
                        val server = resource.server
                        val request = Request.Builder().url(resource.path)

                        // Add authentication if provided
                        if (server.username != null && server.password != null) {
                            val credentials = Credentials.basic(server.username, server.password)
                            request.header("Authorization", credentials)
                        }

                        val response = okHttpClient.newCall(request.build()).execute()
                        if (!response.isSuccessful) {
                            throw IOException("Failed to download file: ${response.code}")
                        }

                        response.body?.byteStream()?.use { input ->
                            cachedFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported protocol: ${resource.server.protocol}")
                }

                // Create and cache the URI
                val uri = Uri.fromFile(cachedFile)
                cachedNetworkPhotos[cacheKey] = uri
                uri
            } catch (e: Exception) {
                Timber.e(e, "Error caching network photo ${resource.name}")
                null
            }
        }
    }

    // Clean up resources
    fun cleanup() {
        stopDiscovery()
        // Clear cached files
        try {
            val cacheDir = File(context.cacheDir, "network_photos")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up network photo cache")
        }
    }

    // States for network discovery
    sealed class DiscoveryState {
        object Idle : DiscoveryState()
        object Searching : DiscoveryState()
        data class Error(val message: String) : DiscoveryState()
    }
}

// Model classes for network sources
data class NetworkServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val username: String? = null,
    val password: String? = null,
    val type: Type = Type.MANUAL,
    val isAvailable: Boolean = true
) {
    enum class Type {
        DISCOVERED,
        MANUAL
    }

    // Safe display name without credentials
    val displayName: String
        get() = "$name ($protocol://$host:$port)"
}

data class NetworkResource(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val server: NetworkServer
)