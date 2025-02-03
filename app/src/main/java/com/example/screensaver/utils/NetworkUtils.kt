package com.example.screensaver.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

/**
 * Utility class for monitoring and managing network connectivity.
 * Provides network state observations and connectivity checks.
 */
class NetworkUtils(private val context: Context) {

    private val connectivityManager = context.getSystemService<ConnectivityManager>()
    private val _networkState = MutableStateFlow(getInitialNetworkState())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val GOOGLE_PHOTOS_HOST = "photos.googleapis.com"
        private const val GOOGLE_PHOTOS_PORT = 443
        private const val CONNECTION_TIMEOUT_MS = 5000
        private val CONNECTIVITY_CHECK_INTERVAL = 30.seconds

        // Minimum speeds for different connection types (in Mbps)
        private const val MIN_WIFI_SPEED = 1.5
        private const val MIN_CELLULAR_SPEED = 1.0
    }

    /**
     * Represents the current network state
     */
    data class NetworkState(
        val isConnected: Boolean = false,
        val connectionType: ConnectionType = ConnectionType.NONE,
        val isMetered: Boolean = true,
        val isGooglePhotosReachable: Boolean = false
    )

    /**
     * Represents different types of network connections
     */
    enum class ConnectionType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }

    /**
     * Provides access to the current network state
     */
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    init {
        registerNetworkCallback()
    }

    /**
     * Registers network callback to monitor connectivity changes
     */
    private fun registerNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetworkState(network)
            }

            override fun onLost(network: Network) {
                _networkState.value = NetworkState()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                updateNetworkState(network, networkCapabilities)
            }
        }

        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    /**
     * Updates the current network state
     */
    private fun updateNetworkState(
        network: Network,
        capabilities: NetworkCapabilities? = connectivityManager?.getNetworkCapabilities(network)
    ) {
        capabilities?.let {
            val connectionType = when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
                it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
                else -> ConnectionType.OTHER
            }

            val isMetered = connectivityManager?.isActiveNetworkMetered ?: true
            val hasInternet = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            _networkState.value = NetworkState(
                isConnected = hasInternet,
                connectionType = connectionType,
                isMetered = isMetered,
                isGooglePhotosReachable = false // Will be updated by connectivity check
            )

            // Check Google Photos connectivity
            checkGooglePhotosConnectivity()
        }
    }

    /**
     * Gets the initial network state
     */
    private fun getInitialNetworkState(): NetworkState {
        val network = connectivityManager?.activeNetwork ?: return NetworkState()
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        return if (capabilities != null) {
            updateNetworkState(network, capabilities)
            _networkState.value
        } else {
            NetworkState()
        }
    }

    /**
     * Checks if Google Photos API is reachable
     */
    private fun checkGooglePhotosConnectivity() {
        Thread {
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(GOOGLE_PHOTOS_HOST, GOOGLE_PHOTOS_PORT),
                        CONNECTION_TIMEOUT_MS
                    )
                    _networkState.value = _networkState.value.copy(isGooglePhotosReachable = true)
                }
            } catch (e: Exception) {
                _networkState.value = _networkState.value.copy(isGooglePhotosReachable = false)
            }
        }.start()
    }

    /**
     * Checks if the current connection is suitable for photo loading
     */
    fun isConnectionSuitableForPhotoLoading(): Boolean {
        val currentState = networkState.value

        return when {
            !currentState.isConnected -> false
            !currentState.isGooglePhotosReachable -> false
            currentState.connectionType == ConnectionType.NONE -> false
            currentState.isMetered && currentState.connectionType == ConnectionType.CELLULAR ->
                checkConnectionSpeed() >= MIN_CELLULAR_SPEED
            currentState.connectionType == ConnectionType.WIFI ->
                checkConnectionSpeed() >= MIN_WIFI_SPEED
            else -> true
        }
    }

    /**
     * Checks the current connection speed
     */
    private fun checkConnectionSpeed(): Double {
        val capabilities = connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capabilities?.linkDownstreamBandwidthKbps?.div(1000.0) ?: 0.0
        } else {
            // Fallback for older Android versions
            Double.POSITIVE_INFINITY // Assume good connection on older devices
        }
    }

    /**
     * Provides a Flow of network state changes
     */
    fun observeNetworkChanges(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getInitialNetworkState())
            }

            override fun onLost(network: Network) {
                trySend(NetworkState())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                updateNetworkState(network, networkCapabilities)
                trySend(_networkState.value)
            }
        }

        connectivityManager?.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            callback
        )

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(callback)
        }
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
            networkCallback = null
        }
    }
}