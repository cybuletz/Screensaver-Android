package com.photostreamr.photos.network

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.core.content.FileProvider
import com.photostreamr.PhotoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.MalformedURLException
import java.util.UUID
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import android.graphics.BitmapFactory
import com.photostreamr.photos.PersistentPhotoCache
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection

@Singleton
class NetworkPhotoManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoRepository: PhotoRepository
) {
    companion object {
        private const val TAG = "NetworkPhotoManager"
        private const val NSD_SERVICE_TYPE = "_smb._tcp."
    }

    // NSD Manager for service discovery
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    // State flows
    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState = _discoveryState.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<NetworkServer>>(emptyList())
    val discoveredServers = _discoveredServers.asStateFlow()

    private val _manualConnections = MutableStateFlow<List<NetworkServer>>(emptyList())
    val manualConnections = _manualConnections.asStateFlow()

    private val _currentBrowsingPath = MutableStateFlow<String?>(null)
    val currentBrowsingPath = _currentBrowsingPath.asStateFlow()

    private val _folderContents = MutableStateFlow<List<NetworkResource>>(emptyList())
    val folderContents = _folderContents.asStateFlow()

    private val persistentPhotoCache: PersistentPhotoCache
        get() = photoRepository.persistentPhotoCache

    // HTTP client for network operations
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Cache for server discovery
    private val discoveredServices = ConcurrentHashMap<String, NetworkServer>()

    // JCIFS context for SMB operations - using lazy initialization
    private val cifsContext: CIFSContext by lazy {
        try {
            Timber.d("Initializing cifsContext lazily")
            // Create Properties object for SMB configuration
            val props = java.util.Properties()

            // Enable both SMB1 and SMB2/3
            props.setProperty("jcifs.smb.client.enableSMB1", "true")

            // Set protocol versions - support SMB1 through SMB3.1.1
            props.setProperty("jcifs.smb.client.minVersion", "SMB1")
            props.setProperty("jcifs.smb.client.maxVersion", "SMB311")

            // Use SMB2 in initial protocol negotiation when possible
            props.setProperty("jcifs.smb.client.useSmb2Negotiation", "true")

            // Tune performance settings
            props.setProperty("jcifs.smb.client.responseTimeout", "30000") // 30 seconds
            props.setProperty("jcifs.smb.client.soTimeout", "35000")       // 35 seconds
            props.setProperty("jcifs.smb.client.connTimeout", "10000")     // 10 seconds
            props.setProperty("jcifs.smb.client.sessionTimeout", "60000")  // 60 seconds

            // Match your existing buffer size for consistency
            props.setProperty("jcifs.smb.client.bufferSize", "1048576")    // 1MB

            // Get the configuration and context correctly
            val config = jcifs.config.PropertyConfiguration(props)
            jcifs.context.BaseContext(config)
        } catch (e: Exception) {
            Timber.e(e, "Error initializing CIFS context")
            _discoveryState.value = DiscoveryState.Error("Failed to initialize CIFS: ${e.message}")

            // Create a default configuration and context as fallback
            val defaultProps = java.util.Properties()
            val defaultConfig = jcifs.config.PropertyConfiguration(defaultProps)
            jcifs.context.BaseContext(defaultConfig)
        }
    }

    sealed class DiscoveryState {
        object Idle : DiscoveryState()
        object Searching : DiscoveryState()
        data class Error(val message: String) : DiscoveryState()
    }

    fun initialize() {
        try {
            // Load saved manual connections
            loadManualConnections()

            // Initialize NSD manager
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

            // Force initialization of cifsContext by accessing it
            Timber.d("Triggering cifsContext initialization")
            cifsContext // This triggers the lazy initialization

            // Log how many servers were loaded
            Timber.d("Loaded ${_manualConnections.value.size} manual server connections")
            Timber.i("SMB configured with SMB1-SMB3 support (preference for SMB2+)")
        } catch (e: Exception) {
            Timber.e(e, "Error initializing NetworkPhotoManager")
            _discoveryState.value = DiscoveryState.Error("Failed to initialize: ${e.message}")
        }
    }

    fun startDiscovery() {
        if (nsdManager == null) {
            Timber.e("NSD Manager is null, cannot start discovery")
            _discoveryState.value = DiscoveryState.Error("Network discovery not available")
            return
        }

        try {
            // Clear previous discovered servers
            discoveredServices.clear()
            _discoveredServers.value = emptyList()

            // Create discovery listener
            val listener = createDiscoveryListener()
            discoveryListener = listener

            // Start discovery
            Timber.i("Starting network service discovery")
            _discoveryState.value = DiscoveryState.Searching
            nsdManager?.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            Timber.d("Discovery started for service type: $NSD_SERVICE_TYPE")
        } catch (e: Exception) {
            Timber.e(e, "Error starting network discovery")
            _discoveryState.value = DiscoveryState.Error("Failed to start discovery: ${e.message}")
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { listener ->
                try {
                    nsdManager?.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Timber.e(e, "Error stopping service discovery")
                }
            }
            discoveryListener = null

            _discoveryState.value = DiscoveryState.Idle
        } catch (e: Exception) {
            Timber.e(e, "Error stopping discovery")
        }
    }

    /**
     * Download and cache a network resource
     */
    suspend fun downloadAndCachePhoto(resource: NetworkResource): String? {
        return withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null

            try {
                if (!resource.isImage) {
                    Log.e(TAG, "Resource is not an image: ${resource.name}")
                    return@withContext null
                }

                // Create a unique ID for this photo
                val photoId = "${resource.server.id}_${resource.path}"

                Log.d(TAG, "Starting download for ${resource.name} from ${resource.server.address}")

                try {
                    // Build SMB URL
                    val smbBase = buildSmbUrl(resource.server)
                    val smbUrl = "${smbBase}/${resource.path}"

                    Log.d(TAG, "Connecting to SMB: $smbUrl")

                    // Create SMB context with credentials if provided
                    val smbContext = if (resource.server.username != null && resource.server.password != null) {
                        cifsContext.withCredentials(
                            jcifs.smb.NtlmPasswordAuthenticator(null, resource.server.username, resource.server.password)
                        )
                    } else {
                        cifsContext
                    }

                    // Access SMB file
                    val smbFile = SmbFile(smbUrl, smbContext)

                    if (!smbFile.exists()) {
                        Log.e(TAG, "SMB file does not exist: $smbUrl")
                        return@withContext null
                    }

                    // Use large buffer for better performance
                    val bufferSize = 1048576  // 1MB buffer

                    // Get input stream with buffering - create it here, don't assign to inputStream yet
                    val bufferedStream = BufferedInputStream(smbFile.inputStream, bufferSize)
                    inputStream = bufferedStream  // Now assign so it gets closed in finally block

                    // Use PersistentPhotoCache to process the image directly from the stream
                    val cacheResult = photoRepository.persistentPhotoCache.cacheNetworkPhoto(bufferedStream, photoId)

                    return@withContext when (cacheResult) {
                        is PersistentPhotoCache.CacheResult.Success -> {
                            Log.d(TAG, "Successfully cached photo: ${resource.name}")
                            cacheResult.cachedUri
                        }
                        is PersistentPhotoCache.CacheResult.Error -> {
                            Log.e(TAG, "Failed to cache photo: ${resource.name} - ${cacheResult.message}")
                            null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading and caching photo", e)
                    return@withContext null
                } finally {
                    try {
                        inputStream?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing input stream", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in downloadAndCachePhoto", e)
                return@withContext null
            }
        }
    }

    suspend fun browseNetworkPath(server: NetworkServer, path: String = ""): Result<List<NetworkResource>> {
        return try {
            withContext(Dispatchers.IO) {
                val resources = mutableListOf<NetworkResource>()

                // Build SMB URL
                val smbBase = buildSmbUrl(server)
                val smbUrl = if (path.isNotEmpty()) "$smbBase/$path/" else "$smbBase/"

                Timber.d("Browsing SMB path: $smbUrl")

                // Create SMB context with credentials if provided
                val context = if (server.username != null && server.password != null) {
                    cifsContext.withCredentials(
                        jcifs.smb.NtlmPasswordAuthenticator(null, server.username, server.password)
                    )
                } else {
                    cifsContext
                }

                // Access SMB share
                val smbFile = SmbFile(smbUrl, context)

                // List files
                smbFile.listFiles().forEach { file ->
                    val isDir = file.isDirectory
                    val isImage = if (!isDir) {
                        val name = file.name.lowercase()
                        name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                name.endsWith(".png") || name.endsWith(".gif") ||
                                name.endsWith(".bmp") || name.endsWith(".webp")
                    } else false

                    resources.add(
                        NetworkResource(
                            server = server,
                            path = path + (if (path.isEmpty()) "" else "/") + file.name,
                            name = file.name,
                            isDirectory = isDir,
                            isImage = isImage,
                            size = if (isDir) 0 else file.length(),
                            lastModified = file.lastModified()
                        )
                    )
                }

                // Sort directories first, then by name
                val sortedResources = resources.sortedWith(
                    compareByDescending<NetworkResource> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )

                // Update state
                _currentBrowsingPath.value = path
                _folderContents.value = sortedResources

                Result.success(sortedResources)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error browsing network path")
            Result.failure(e)
        }
    }

    suspend fun getCachedPhotoUri(resource: NetworkResource): Uri? {
        return try {
            withContext(Dispatchers.IO) {
                if (!resource.isImage) {
                    Log.d(TAG, "Resource is not an image: ${resource.name}")
                    return@withContext null
                }

                // Create cache directory
                val cacheDir = File(context.cacheDir, "network_photos")
                cacheDir.mkdirs()

                // Generate unique, safe filename - shorter to avoid path length issues
                val safeName = resource.name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                val fileName = "${resource.server.id.substring(0, 8)}_${safeName}"
                val cacheFile = File(cacheDir, fileName)

                // Check if already cached with valid size
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    try {
                        // Verify file is a valid image
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(cacheFile.absolutePath, options)

                        if (options.outWidth > 0 && options.outHeight > 0) {
                            Log.d(TAG, "Using valid cached image file for ${resource.name}: ${cacheFile.path}")

                            try {
                                return@withContext FileProvider.getUriForFile(
                                    context,
                                    "com.photostreamr.fileprovider",
                                    cacheFile
                                )
                            } catch (e: IllegalArgumentException) {
                                Log.e(TAG, "FileProvider error for cached file: ${cacheFile.path}", e)
                                // Try a different approach if FileProvider fails
                                return@withContext Uri.fromFile(cacheFile)
                            }
                        } else {
                            Log.d(TAG, "Cached file exists but is not a valid image, re-downloading")
                            cacheFile.delete()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking cached file validity", e)
                        cacheFile.delete()
                    }
                }

                // Download file
                Log.d(TAG, "Downloading image ${resource.name} to ${cacheFile.path}")

                try {
                    // Build SMB URL
                    val smbUrl = buildSmbUrl(resource.server) + "/" + resource.path
                    Log.d(TAG, "Connecting to SMB: $smbUrl")

                    val smbContext = if (resource.server.username != null && resource.server.password != null) {
                        cifsContext.withCredentials(
                            jcifs.smb.NtlmPasswordAuthenticator(null, resource.server.username, resource.server.password)
                        )
                    } else {
                        cifsContext
                    }

                    // Get file via SMB
                    val smbFile = SmbFile(smbUrl, smbContext)

                    if (!smbFile.exists()) {
                        Log.e(TAG, "SMB file does not exist: $smbUrl")
                        return@withContext null
                    }

                    val fileSize = smbFile.length()
                    if (fileSize <= 0) {
                        Log.e(TAG, "SMB file has zero or negative size: $smbUrl")
                        return@withContext null
                    }

                    // Create temporary file first to ensure complete download
                    val tempFile = File(cacheDir, "temp_${fileName}")
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }

                    // Use much larger buffer size for faster transfers
                    val bufferSize = 1048576  // 1MB buffer (8x larger for better performance)

                    // Use buffered streams with fixed buffer size
                    val inputStream = BufferedInputStream(smbFile.inputStream, bufferSize)
                    val outputStream = BufferedOutputStream(FileOutputStream(tempFile), bufferSize)

                    // Copy with a progress counter
                    val buffer = ByteArray(bufferSize)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    val startTime = System.currentTimeMillis()

                    try {
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            // Log progress less frequently for better performance
                            if (totalBytesRead >= fileSize / 2 && totalBytesRead - bytesRead < fileSize / 2) {
                                Log.d(TAG, "Download progress: 50% ($totalBytesRead/$fileSize bytes)")
                            }
                        }
                        outputStream.flush()

                        val endTime = System.currentTimeMillis()
                        val downloadSpeed = totalBytesRead / ((endTime - startTime) / 1000.0) // bytes per second
                        val speedInMB = downloadSpeed / (1024 * 1024)
                        Log.d(TAG, "Download complete in ${endTime - startTime}ms (${String.format("%.2f", speedInMB)} MB/s)")

                    } finally {
                        try { outputStream.close() } catch (e: Exception) { Log.e(TAG, "Error closing output stream", e) }
                        try { inputStream.close() } catch (e: Exception) { Log.e(TAG, "Error closing input stream", e) }
                    }

                    // Verify download is complete
                    val downloadSuccess = tempFile.exists() && tempFile.length() > 0

                    if (downloadSuccess && tempFile.length() >= fileSize * 0.99) {
                        // Verify file is actually a valid image
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(tempFile.absolutePath, options)

                        if (options.outWidth > 0 && options.outHeight > 0) {
                            // It's a valid image, move to final location
                            if (cacheFile.exists()) {
                                cacheFile.delete()
                            }

                            if (tempFile.renameTo(cacheFile)) {
                                Log.d(TAG, "Successfully downloaded valid image ${resource.name} (${cacheFile.length()} bytes)")

                                try {
                                    return@withContext FileProvider.getUriForFile(
                                        context,
                                        "com.photostreamr.fileprovider",
                                        cacheFile
                                    )
                                } catch (e: IllegalArgumentException) {
                                    // If FileProvider fails, try direct file URI
                                    Log.e(TAG, "FileProvider error, falling back to direct file URI", e)
                                    return@withContext Uri.fromFile(cacheFile)
                                }
                            } else {
                                Log.e(TAG, "Failed to move temp file to final location")
                                return@withContext null
                            }
                        } else {
                            Log.e(TAG, "Downloaded file is not a valid image")
                            tempFile.delete()
                            return@withContext null
                        }
                    } else {
                        Log.e(TAG, "Incomplete download: ${tempFile.length()} of $fileSize bytes for ${resource.name}")
                        tempFile.delete()
                        return@withContext null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download SMB file for ${resource.name}", e)
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getCachedPhotoUri for ${resource.name}", e)
            null
        }
    }

    fun addManualServer(server: NetworkServer) {
        val currentList = _manualConnections.value.toMutableList()
        currentList.add(server)
        _manualConnections.value = currentList

        // Save to preferences
        saveManualConnections()
    }

    fun removeManualServer(server: NetworkServer) {
        val currentList = _manualConnections.value.toMutableList()
        currentList.removeAll { it.id == server.id }
        _manualConnections.value = currentList

        // Save to preferences
        saveManualConnections()
    }

    private fun saveManualConnections() {
        try {
            val sharedPrefs = context.getSharedPreferences("network_photo_manager", Context.MODE_PRIVATE)
            val servers = _manualConnections.value

            // Convert servers to JSON
            val jsonArray = JSONArray()
            servers.forEach { server ->
                val serverJson = JSONObject().apply {
                    put("id", server.id)
                    put("name", server.name)
                    put("address", server.address)
                    put("username", server.username ?: "")
                    put("password", server.password ?: "") // Note: Consider encryption for passwords
                    put("isManual", server.isManual)
                }
                jsonArray.put(serverJson)
            }

            // Save to SharedPreferences
            sharedPrefs.edit().putString("manual_servers", jsonArray.toString()).apply()

            Timber.d("Saved ${servers.size} manual connections")
        } catch (e: Exception) {
            Timber.e(e, "Error saving manual connections")
        }
    }

    private fun loadManualConnections() {
        try {
            val sharedPrefs = context.getSharedPreferences("network_photo_manager", Context.MODE_PRIVATE)
            val serversJson = sharedPrefs.getString("manual_servers", "[]")

            val servers = mutableListOf<NetworkServer>()
            val jsonArray = JSONArray(serversJson)

            for (i in 0 until jsonArray.length()) {
                val serverJson = jsonArray.getJSONObject(i)
                val server = NetworkServer(
                    id = serverJson.getString("id"),
                    name = serverJson.getString("name"),
                    address = serverJson.getString("address"),
                    username = serverJson.getString("username").let { if (it.isEmpty()) null else it },
                    password = serverJson.getString("password").let { if (it.isEmpty()) null else it },
                    isManual = serverJson.getBoolean("isManual")
                )
                servers.add(server)
            }

            _manualConnections.value = servers
            Timber.d("Loaded ${servers.size} manual connections")
        } catch (e: Exception) {
            Timber.e(e, "Error loading manual connections")
        }
    }

    private fun buildSmbUrl(server: NetworkServer): String {
        return if (server.address.startsWith("smb://")) {
            server.address.trimEnd('/')
        } else {
            "smb://${server.address.trimEnd('/')}"
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("Service discovery started for $serviceType")
                _discoveryState.value = DiscoveryState.Searching
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Timber.d("Service found: ${serviceInfo.serviceName} type=${serviceInfo.serviceType}")

                // Log all service found, not just SMB services
                if (serviceInfo.serviceType == NSD_SERVICE_TYPE) {
                    Timber.i("SMB Service found: ${serviceInfo.serviceName}")
                    resolveService(serviceInfo)
                } else {
                    Timber.d("Non-SMB service found: ${serviceInfo.serviceName} (type=${serviceInfo.serviceType})")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.d("Service lost: ${serviceInfo.serviceName}")
                discoveredServices.remove(serviceInfo.serviceName)
                updateDiscoveredServers()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Timber.d("Discovery stopped: $serviceType")
                _discoveryState.value = DiscoveryState.Idle
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery start failed: $errorCode for $serviceType")
                _discoveryState.value = DiscoveryState.Error("Failed to start discovery: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.e("Discovery stop failed: $errorCode for $serviceType")
            }
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            Timber.d("Resolving service: ${serviceInfo.serviceName}")

            // Create resolve listener
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    Timber.d("Service resolved: ${resolvedService.serviceName}, host=${resolvedService.host}, port=${resolvedService.port}")

                    // Log all service attributes
                    val attributeNames = resolvedService.attributes?.keys
                    if (attributeNames != null) {
                        for (name in attributeNames) {
                            val value = resolvedService.attributes[name]
                            Timber.d("Attribute: $name = ${value?.toString(Charset.defaultCharset())}")
                        }
                    }

                    // Create server object
                    val address = "${resolvedService.host.hostAddress}"
                    val server = NetworkServer(
                        id = UUID.randomUUID().toString(),
                        name = resolvedService.serviceName,
                        address = address,
                        isManual = false
                    )

                    // Add to discovered services
                    discoveredServices[resolvedService.serviceName] = server
                    updateDiscoveredServers()
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.e("Failed to resolve service: ${serviceInfo.serviceName}, error=$errorCode")
                }
            }

            // Resolve service
            resolveListener = listener
            nsdManager?.resolveService(serviceInfo, listener)
        } catch (e: Exception) {
            Timber.e(e, "Error resolving service")
        }
    }

    private fun updateDiscoveredServers() {
        val servers = discoveredServices.values.toList()
        _discoveredServers.value = servers
    }
}