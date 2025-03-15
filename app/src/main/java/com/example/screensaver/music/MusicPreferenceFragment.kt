package com.example.screensaver.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import android.view.KeyEvent
import android.widget.ProgressBar
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import com.example.screensaver.ScreensaverApplication
import com.example.screensaver.widgets.WidgetManager


@AndroidEntryPoint
class MusicPreferenceFragment : PreferenceFragmentCompat() {
    companion object {
        private const val TAG = "MusicPreferenceFragment"
        private const val MUSIC_SOURCE_SPOTIFY = "spotify"
        private const val MUSIC_SOURCE_LOCAL = "local"
        private const val MUSIC_SOURCE_RADIO = "radio"
    }

    @Inject
    lateinit var spotifyManager: SpotifyManager

    @Inject
    lateinit var spotifyAuthManager: SpotifyAuthManager

    @Inject
    lateinit var spotifyPreferences: SpotifyPreferences

    @Inject
    lateinit var radioManager: RadioManager

    @Inject
    lateinit var radioPreferences: RadioPreferences

    @Inject
    lateinit var widgetManager: WidgetManager

    private var currentMusicSource: String = MUSIC_SOURCE_SPOTIFY

    private val spotifyAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Timber.d("Spotify auth result received: resultCode=${result.resultCode}, data=${result.data?.data}")

        // Handle the auth response
        spotifyAuthManager.handleAuthResponse(
            SpotifyAuthManager.REQUEST_CODE,
            result.resultCode,
            result.data
        )

        // Bring our activity to front properly
        requireActivity().apply {
            val intent = Intent(this, this::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        }

        // Update UI based on auth state
        when (val state = spotifyAuthManager.authState.value) {
            is SpotifyAuthManager.AuthState.Authenticated -> {
                Timber.d("Spotify authentication successful")
                spotifyPreferences.setEnabled(true)
                spotifyManager.retry()
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = true
            }
            is SpotifyAuthManager.AuthState.Error -> {
                Timber.e("Spotify authentication failed: ${state.error.message}")
                spotifyPreferences.setEnabled(false)
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
                Toast.makeText(
                    requireContext(),
                    "Authentication failed: ${state.error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                Timber.d("Spotify authentication cancelled or unknown state")
                spotifyPreferences.setEnabled(false)
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
            }
        }
        updateSpotifyLoginSummary()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.music_preferences, rootKey)

        findPreference<ListPreference>("music_source")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val newSource = newValue.toString()

                // Disable previous source first and ensure it's fully disconnected
                when (currentMusicSource) {
                    MUSIC_SOURCE_RADIO -> {
                        radioManager.disconnect()
                        radioPreferences.setEnabled(false)
                        findPreference<SwitchPreferenceCompat>("radio_enabled")?.isChecked = false
                    }
                    MUSIC_SOURCE_SPOTIFY -> {
                        if (spotifyPreferences.isEnabled()) {
                            spotifyPreferences.setEnabled(false)
                            spotifyPreferences.setConnectionState(false)
                            spotifyManager.disconnect()
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
                        }
                    }
                }

                // Update current source
                currentMusicSource = newSource

                // Force immediate widget update
                widgetManager.updateMusicWidgetBasedOnSource()

                // Update UI
                updateVisiblePreferences(newSource)
                setupRadioPreferences()

                true
            }
        }

        // Initialize preferences
        updateVisiblePreferences(currentMusicSource)
        setupRadioPreferences()

        // Check initial state for widget visibility
        widgetManager.updateMusicWidgetBasedOnSource()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpotifyPreferences()
        setupSpotifyObservers()
    }

    private fun setupSpotifyObservers() {
        // Auth state observer
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                spotifyAuthManager.authState.collect { state ->
                    updateSpotifyLoginSummary()
                    when (state) {
                        is SpotifyAuthManager.AuthState.Authenticated -> {
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = true
                            spotifyPreferences.setEnabled(true)
                            spotifyPreferences.setConnectionState(true)
                            spotifyManager.retry()
                        }
                        is SpotifyAuthManager.AuthState.Error -> {
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
                            spotifyPreferences.setEnabled(false)
                            spotifyPreferences.setConnectionState(false)
                            Toast.makeText(
                                requireContext(),
                                "Authentication failed: ${state.error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> {
                            val isEnabled = spotifyPreferences.isEnabled()
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = isEnabled
                            if (!isEnabled) {
                                spotifyPreferences.setConnectionState(false)
                            }
                        }
                    }
                    updateVisiblePreferences(currentMusicSource)
                }
            }
        }

        // Connection state observer
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                spotifyManager.connectionState.collect { state ->
                    updateSpotifyLoginSummary()
                    when (state) {
                        is SpotifyManager.ConnectionState.Connected -> {
                            val savedSummary = spotifyPreferences.getPlaylistSummary()
                            if (savedSummary != null) {
                                findPreference<Preference>("spotify_playlist")?.setSummary(savedSummary)
                            } else {
                                spotifyPreferences.getSelectedPlaylist()?.let { uri ->
                                    spotifyManager.getPlaylistInfo(
                                        uri = uri,
                                        callback = { playlist ->
                                            val summary = playlist?.title ?: "Select playlist"
                                            spotifyPreferences.setPlaylistSummary(summary)
                                            findPreference<Preference>("spotify_playlist")?.setSummary(summary)
                                        },
                                        errorCallback = {
                                            findPreference<Preference>("spotify_playlist")?.setSummary("Select playlist")
                                        }
                                    )
                                }
                            }
                        }
                        else -> {
                            spotifyPreferences.getPlaylistSummary()?.let { savedSummary ->
                                findPreference<Preference>("spotify_playlist")?.setSummary(savedSummary)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateVisiblePreferences(musicSource: String) {
        Timber.d("Updating visible preferences for music source: $musicSource")

        // Hide ALL preferences first
        findPreference<Preference>("spotify_enabled")?.isVisible = false
        findPreference<Preference>("spotify_login")?.isVisible = false
        findPreference<Preference>("spotify_playlist")?.isVisible = false
        findPreference<Preference>("spotify_autoplay")?.isVisible = false
        findPreference<Preference>("local_music_folder")?.isVisible = false
        findPreference<Preference>("radio_enabled")?.isVisible = false
        findPreference<Preference>("radio_station_search")?.isVisible = false
        findPreference<Preference>("radio_favorites")?.isVisible = false
        findPreference<Preference>("radio_recent")?.isVisible = false

        // Show only the relevant ones based on selected source
        when (musicSource) {
            MUSIC_SOURCE_SPOTIFY -> {
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isVisible = true
                val showSpotifyOptions = findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked == true
                findPreference<Preference>("spotify_login")?.isVisible = showSpotifyOptions
                findPreference<Preference>("spotify_playlist")?.isVisible = showSpotifyOptions
                findPreference<Preference>("spotify_autoplay")?.isVisible = showSpotifyOptions
            }
            MUSIC_SOURCE_RADIO -> {
                // Show radio switch first
                findPreference<SwitchPreferenceCompat>("radio_enabled")?.isVisible = true

                // Check if radio is enabled
                val radioEnabled = radioPreferences.isEnabled()

                // Show options if radio is enabled
                findPreference<Preference>("radio_station_search")?.isVisible = radioEnabled
                findPreference<Preference>("radio_favorites")?.isVisible = radioEnabled
                findPreference<Preference>("radio_recent")?.isVisible = radioEnabled

                // Force preference screen to redraw
                preferenceScreen.notifyDependencyChange(false)
            }
            MUSIC_SOURCE_LOCAL -> {
                findPreference<Preference>("local_music_folder")?.isVisible = true
            }
        }
    }

    private fun setupSpotifyPreferences() {
        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.apply {
            isChecked = spotifyPreferences.isEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    if (!spotifyManager.isSpotifyInstalled()) {
                        showSpotifyInstallDialog()
                        false
                    } else {
                        try {
                            val authIntent = spotifyAuthManager.getAuthIntent(requireActivity())
                            spotifyAuthLauncher.launch(authIntent)
                            // Simply use the injected widgetManager
                            widgetManager.updateMusicWidgetBasedOnSource()
                            false
                        } catch (e: Exception) {
                            Toast.makeText(
                                requireContext(),
                                "Error starting Spotify authentication",
                                Toast.LENGTH_LONG
                            ).show()
                            false
                        }
                    }
                } else {
                    spotifyPreferences.setEnabled(false)
                    spotifyPreferences.setConnectionState(false)
                    spotifyManager.disconnect()
                    true
                }
            }
        }

        findPreference<Preference>("spotify_login")?.apply {
            setOnPreferenceClickListener {
                if (spotifyAuthManager.authState.value is SpotifyAuthManager.AuthState.Authenticated) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Spotify Account")
                        .setMessage("Do you want to disconnect your Spotify account?")
                        .setPositiveButton("Disconnect") { _, _ ->
                            spotifyAuthManager.logout()
                            updateSpotifyLoginSummary()
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                        .show()
                } else {
                    try {
                        val authIntent = spotifyAuthManager.getAuthIntent(requireActivity())
                        spotifyAuthLauncher.launch(authIntent)
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Error starting Spotify authentication",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
        }

        findPreference<SwitchPreferenceCompat>("spotify_autoplay")?.apply {
            isChecked = spotifyPreferences.isAutoplayEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                spotifyPreferences.setAutoplayEnabled(newValue as Boolean)
                true
            }
        }

        findPreference<Preference>("spotify_playlist")?.apply {
            setOnPreferenceClickListener {
                if (!spotifyPreferences.isEnabled()) {
                    Toast.makeText(
                        requireContext(),
                        "Please enable and connect to Spotify first",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnPreferenceClickListener true
                }

                if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
                    Toast.makeText(
                        requireContext(),
                        "Connecting to Spotify...",
                        Toast.LENGTH_SHORT
                    ).show()
                    spotifyManager.retry()
                    return@setOnPreferenceClickListener true
                }

                val loadingDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Loading Playlists")
                    .setMessage("Please wait...")
                    .setCancelable(false)
                    .create()
                loadingDialog.show()

                spotifyManager.getPlaylists(
                    callback = { playlists ->
                        loadingDialog.dismiss()
                        Timber.d("Available playlists: ${playlists.map { "${it.title} (${it.uri})"}}")

                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Select Playlist")
                            .setItems(playlists.map { it.title }.toTypedArray()) { _, which ->
                                val selectedPlaylist = playlists[which]
                                Timber.d("Selected playlist: ${selectedPlaylist.title} with URI: ${selectedPlaylist.uri}")

                                // Update these two lines:
                                spotifyPreferences.setSelectedPlaylistWithTitle(selectedPlaylist.uri, selectedPlaylist.title)
                                summary = selectedPlaylist.title

                                Toast.makeText(
                                    requireContext(),
                                    "Selected: ${selectedPlaylist.title}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    },
                    errorCallback = { error ->
                        loadingDialog.dismiss()
                        Timber.e(error, "Failed to load playlists")
                        Toast.makeText(
                            requireContext(),
                            "Error loading playlists: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
                true
            }

            // Update initial preference description to show currently selected playlist
            spotifyPreferences.getPlaylistSummary()?.let { savedSummary ->
                setSummary(savedSummary)
            } ?: run {
                // If no summary is saved, try to get playlist info from Spotify
                spotifyPreferences.getSelectedPlaylist()?.let { uri ->
                    lifecycleScope.launch {
                        spotifyManager.getPlaylistInfo(
                            uri = uri,
                            callback = { playlist ->
                                val summary = playlist?.title ?: "Select playlist"
                                spotifyPreferences.setPlaylistSummary(summary)  // Save the summary
                                findPreference<Preference>("spotify_playlist")?.setSummary(summary)
                            },
                            errorCallback = {
                                findPreference<Preference>("spotify_playlist")?.setSummary("Select playlist")
                            }
                        )
                    }
                } ?: run {
                    setSummary("Select playlist")
                }
            }
        }
    }

    private fun updateSpotifyLoginSummary() {
        findPreference<Preference>("spotify_login")?.apply {
            // First check if Spotify is enabled at all
            if (!spotifyPreferences.isEnabled()) {
                summary = "Not connected"
                isEnabled = true
                return@apply
            }

            // Then check the current connection state
            when (val state = spotifyManager.connectionState.value) {
                is SpotifyManager.ConnectionState.Connected -> {
                    // Get user info when connected
                    spotifyManager.getCurrentUser(
                        callback = { user ->
                            // Ensure we update UI on main thread
                            Handler(Looper.getMainLooper()).post {
                                // Double check we're still connected when callback returns
                                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                                    val displayName = user?.displayName ?: user?.id
                                    summary = if (displayName != null) {
                                        "Connected as $displayName"
                                    } else {
                                        "Connected to Spotify"
                                    }
                                    isEnabled = true
                                }
                            }
                        },
                        errorCallback = { error ->
                            Handler(Looper.getMainLooper()).post {
                                Timber.e(error, "Failed to get Spotify user info")
                                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                                    summary = "Connected to Spotify"
                                    isEnabled = true
                                }
                            }
                        }
                    )
                    // Set temporary state while we fetch user info
                    summary = "Connected to Spotify"
                    isEnabled = true
                }
                is SpotifyManager.ConnectionState.Error -> {
                    summary = "Connection error: ${(state.error.message ?: "Unknown error")}"
                    isEnabled = true
                }
                is SpotifyManager.ConnectionState.Disconnected -> {
                    val authState = spotifyAuthManager.authState.value
                    summary = when {
                        authState is SpotifyAuthManager.AuthState.Authenticated &&
                                spotifyPreferences.wasConnected() -> {
                            "Disconnected - tap to reconnect"
                        }
                        authState is SpotifyAuthManager.AuthState.Authenticated -> {
                            "Tap to connect"
                        }
                        else -> {
                            "Not connected"
                        }
                    }
                    isEnabled = true
                }
            }

            Timber.d("""
            Spotify login summary updated:
            Connection state: ${spotifyManager.connectionState.value}
            Auth state: ${spotifyAuthManager.authState.value}
            Was connected: ${spotifyPreferences.wasConnected()}
            Is enabled: ${spotifyPreferences.isEnabled()}
            Current summary: $summary
        """.trimIndent())
        }
    }

    private fun showSpotifyInstallDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.spotify_not_installed_title)
            .setMessage(R.string.spotify_not_installed_message)
            .setPositiveButton(R.string.install) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=com.spotify.music")
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.spotify.music")
                        )
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupRadioPreferences() {
        // Radio Enable Switch
        findPreference<SwitchPreferenceCompat>("radio_enabled")?.apply {
            isChecked = radioPreferences.isEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                radioPreferences.setEnabled(enabled)

                // Force immediate UI update
                findPreference<Preference>("radio_station_search")?.isVisible = enabled
                findPreference<Preference>("radio_favorites")?.isVisible = enabled
                findPreference<Preference>("radio_recent")?.isVisible = enabled

                if (!enabled) {
                    radioManager.disconnect()
                } else {
                    // Update music widget visibility when radio is enabled
                    widgetManager.updateMusicWidgetBasedOnSource()
                }
                true
            }
        }

        // Station Search
        findPreference<Preference>("radio_station_search")?.apply {
            isVisible = radioPreferences.isEnabled()
            setOnPreferenceClickListener {
                if (radioPreferences.isEnabled()) {
                    showStationSearchDialog()
                } else {
                    Toast.makeText(requireContext(), "Please enable radio first", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        // Recent Stations
        findPreference<Preference>("radio_recent")?.apply {
            isVisible = radioPreferences.isEnabled()
            setOnPreferenceClickListener {
                if (radioPreferences.isEnabled()) {
                    showRecentStationsDialog()
                } else {
                    Toast.makeText(requireContext(), "Please enable radio first", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        // Favorites Management
        findPreference<Preference>("radio_favorites")?.apply {
            isVisible = radioPreferences.isEnabled()
            setOnPreferenceClickListener {
                if (radioPreferences.isEnabled()) {
                    showFavoritesDialog()
                } else {
                    Toast.makeText(requireContext(), "Please enable radio first", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }

    private fun showStationSearchDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_radio_station_search, null)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Search Radio Stations")
            .setView(dialogView)
            .create()

        val searchInput = dialogView.findViewById<TextInputEditText>(R.id.search_input)
        val stationsList = dialogView.findViewById<RecyclerView>(R.id.stations_list)
        val loadingIndicator = dialogView.findViewById<ProgressBar>(R.id.loading_indicator)
        val noResultsText = dialogView.findViewById<TextView>(R.id.no_results_text)

        // Create adapter reference that can be captured by lambda
        lateinit var stationsAdapter: RadioStationAdapter

        stationsAdapter = RadioStationAdapter(
            onStationClick = { station ->
                radioManager.playStation(station)
                radioPreferences.setLastStation(station)
                dialog.dismiss()
            },
            onFavoriteClick = { station ->
                val favorites = radioPreferences.getFavoriteStations().toMutableList()
                if (favorites.any { it.id == station.id }) {
                    favorites.removeAll { it.id == station.id }
                    Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                } else {
                    favorites.add(station)
                    Toast.makeText(requireContext(), "Added to favorites", Toast.LENGTH_SHORT).show()
                }
                radioPreferences.saveFavoriteStations(favorites)
                stationsAdapter.notifyDataSetChanged()
            },
            getFavoriteStatus = { station ->
                radioPreferences.getFavoriteStations().any { it.id == station.id }
            }
        )

        stationsList.layoutManager = LinearLayoutManager(context)
        stationsList.adapter = stationsAdapter

        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {

                val query = searchInput.text?.toString() ?: ""
                if (query.length >= 3) {
                    loadingIndicator.visibility = View.VISIBLE
                    stationsList.visibility = View.GONE
                    noResultsText.visibility = View.GONE

                    radioManager.searchStations(query) { stations ->
                        loadingIndicator.visibility = View.GONE
                        if (stations.isEmpty()) {
                            noResultsText.visibility = View.VISIBLE
                            stationsList.visibility = View.GONE
                        } else {
                            noResultsText.visibility = View.GONE
                            stationsList.visibility = View.VISIBLE
                            stationsAdapter.submitList(stations)
                        }
                    }
                }
                true
            } else false
        }

        dialog.show()
    }

    private fun showRecentStationsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_radio_stations_list, null)

        val stationsList = dialogView.findViewById<RecyclerView>(R.id.stations_list)

        // Create adapter reference that can be captured by lambda
        lateinit var recentAdapter: RadioStationAdapter

        recentAdapter = RadioStationAdapter(
            onStationClick = { station ->
                radioManager.playStation(station)
                radioPreferences.setLastStation(station)
                radioPreferences.addToRecentStations(station)
            },
            onFavoriteClick = { station ->
                val favorites = radioPreferences.getFavoriteStations().toMutableList()
                if (favorites.any { it.id == station.id }) {
                    favorites.removeAll { it.id == station.id }
                    Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
                } else {
                    favorites.add(station)
                    Toast.makeText(requireContext(), "Added to favorites", Toast.LENGTH_SHORT).show()
                }
                radioPreferences.saveFavoriteStations(favorites)
                recentAdapter.notifyDataSetChanged()
            },
            getFavoriteStatus = { station ->
                radioPreferences.getFavoriteStations().any { it.id == station.id }
            }
        )

        stationsList.layoutManager = LinearLayoutManager(context)
        stationsList.adapter = recentAdapter

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Recent Stations")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        dialog.show()

        // Load and display the recent stations
        val recentStations = radioPreferences.getRecentStations()
        if (recentStations.isEmpty()) {
            Toast.makeText(requireContext(), "No recent stations", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        } else {
            recentAdapter.submitList(recentStations)
        }
    }

    private fun showFavoritesDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_radio_favorites, null)

        val stationsList = dialogView.findViewById<RecyclerView>(R.id.stations_list)

        // Create adapter reference that can be captured by lambda
        lateinit var favoritesAdapter: RadioStationAdapter

        favoritesAdapter = RadioStationAdapter(
            onStationClick = { station ->
                radioManager.playStation(station)
                radioPreferences.setLastStation(station)
            },
            onFavoriteClick = { station ->
                val favorites = radioPreferences.getFavoriteStations().toMutableList()
                favorites.removeAll { it.id == station.id }
                radioPreferences.saveFavoriteStations(favorites)
                favoritesAdapter.submitList(radioPreferences.getFavoriteStations())
                Toast.makeText(requireContext(), "Removed from favorites", Toast.LENGTH_SHORT).show()
            },
            getFavoriteStatus = { true }
        )

        stationsList.layoutManager = LinearLayoutManager(context)
        stationsList.adapter = favoritesAdapter

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Favorite Stations")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()

        favoritesAdapter.submitList(radioPreferences.getFavoriteStations())
    }

    private fun disableRadio() {
        if (radioPreferences.isEnabled()) {
            radioPreferences.setEnabled(false)
            radioManager.disconnect()
        }
    }

    fun applyChanges() {
        Timber.d("Applying music source changes")
    }

    fun cancelChanges() {
        Timber.d("Canceling music source changes")
    }
}