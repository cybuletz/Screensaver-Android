package com.photostreamr.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
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
import com.photostreamr.R
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
import com.photostreamr.widgets.WidgetManager
import android.widget.ImageView
import android.widget.BaseAdapter
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.photostreamr.version.FeatureManager
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.TimeUnit
import android.app.Activity
import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.photostreamr.databinding.ItemLocalTrackBinding

@AndroidEntryPoint
class MusicPreferenceFragment : PreferenceFragmentCompat() {
    companion object {
        private const val TAG = "MusicPreferenceFragment"
        private const val MUSIC_SOURCE_SPOTIFY = "spotify"
        private const val MUSIC_SOURCE_LOCAL = "local"
        private const val MUSIC_SOURCE_RADIO = "radio"
        private const val REQUEST_CODE_DIRECTORY = 1002

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
    lateinit var localMusicManager: LocalMusicManager

    @Inject
    lateinit var localMusicPreferences: LocalMusicPreferences

    @Inject
    lateinit var widgetManager: WidgetManager

    private var currentMusicSource: String = MUSIC_SOURCE_SPOTIFY

    private var onPreferenceChangeCallback: (() -> Unit)? = null

    private var localMusicDialog: AlertDialog? = null


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

    private fun showLocalMusicDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_local_music, null)

        val directoryText = dialogView.findViewById<TextView>(R.id.directory_text)
        val browseButton = dialogView.findViewById<MaterialButton>(R.id.browse_button)
        val scanButton = dialogView.findViewById<MaterialButton>(R.id.scan_button)
        val tracksList = dialogView.findViewById<RecyclerView>(R.id.tracks_list)
        val loadingIndicator = dialogView.findViewById<ProgressBar>(R.id.loading_indicator)
        val noTracksText = dialogView.findViewById<TextView>(R.id.no_tracks_text)
        val shuffleSwitch = dialogView.findViewById<SwitchCompat>(R.id.shuffle_switch)
        val autoplaySwitch = dialogView.findViewById<SwitchCompat>(R.id.autoplay_switch)

        // Initialize switches
        shuffleSwitch.isChecked = localMusicPreferences.isShuffleEnabled()
        autoplaySwitch.isChecked = localMusicPreferences.isAutoplayEnabled()

        // Set current directory
        directoryText.text = localMusicPreferences.getMusicDirectory()

        // Setup RecyclerView
        val tracksAdapter = LocalMusicAdapter(
            onTrackClick = { track ->
                localMusicManager.playTrack(track)
                localMusicPreferences.setLastTrack(track)
                localMusicPreferences.setWasPlaying(true)
            }
        )

        tracksList.layoutManager = LinearLayoutManager(requireContext())
        tracksList.adapter = tracksAdapter

        // Setup scan button
        scanButton.setOnClickListener {
            // Show loading state
            loadingIndicator.visibility = View.VISIBLE
            tracksList.visibility = View.GONE
            noTracksText.visibility = View.GONE

            // Use a coroutine for scanning
            lifecycleScope.launch {
                try {
                    val musicDirectory = localMusicPreferences.getMusicDirectory()
                    Timber.d("Scanning music directory: $musicDirectory")

                    localMusicManager.scanMusicFiles { tracks ->
                        // Make sure we update UI on main thread
                        requireActivity().runOnUiThread {
                            loadingIndicator.visibility = View.GONE

                            if (tracks.isEmpty()) {
                                Timber.d("No music files found")
                                noTracksText.visibility = View.VISIBLE
                                tracksList.visibility = View.GONE
                            } else {
                                Timber.d("Found ${tracks.size} music files")
                                noTracksText.visibility = View.GONE
                                tracksList.visibility = View.VISIBLE
                                tracksAdapter.submitList(tracks)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error scanning for music files")
                    requireActivity().runOnUiThread {
                        loadingIndicator.visibility = View.GONE
                        noTracksText.visibility = View.VISIBLE
                        noTracksText.text = "Error scanning: ${e.message}"
                    }
                }
            }
        }

        // Browse button listener
        browseButton.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivityForResult(intent, REQUEST_CODE_DIRECTORY)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    requireContext(),
                    "No file browser available",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // Switch listeners
        shuffleSwitch.setOnCheckedChangeListener { _, isChecked ->
            localMusicPreferences.setShuffleEnabled(isChecked)
            localMusicManager.setShuffleMode(isChecked)
        }

        autoplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            localMusicPreferences.setAutoplayEnabled(isChecked)
        }

        // Create and save dialog reference
        localMusicDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Local Music")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        localMusicDialog?.show()

        // Initial scan if folder exists
        scanButton.performClick()
    }



    private fun setupLocalMusicPreferences() {
        findPreference<Preference>("local_music_folder")?.apply {
            isVisible = currentMusicSource == MUSIC_SOURCE_LOCAL
            val musicDirectory = localMusicPreferences.getMusicDirectory()
            summary = musicDirectory

            setOnPreferenceClickListener {
                localMusicPreferences.setEnabled(true) // Enable the local music feature
                showLocalMusicDialog()
                return@setOnPreferenceClickListener true

                showLocalMusicDialog()
                true
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_DIRECTORY && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    // Take persistent permission - use only valid flags
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)

                    // Store the URI directly as the music directory path
                    val uriString = uri.toString()
                    Timber.d("Selected directory URI: $uriString")

                    // Update preferences with URI string
                    localMusicPreferences.setMusicDirectory(uriString)

                    // Update dialog if it's showing
                    localMusicDialog?.findViewById<TextView>(R.id.directory_text)?.text = uriString

                    // Trigger scan in the existing dialog
                    localMusicDialog?.findViewById<MaterialButton>(R.id.scan_button)?.performClick()
                } catch (e: Exception) {
                    Timber.e(e, "Error accessing directory: $uri")
                    Toast.makeText(requireContext(), "Failed to access selected folder: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setOnPreferenceChangeCallback(callback: () -> Unit) {
        onPreferenceChangeCallback = callback
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        onPreferenceChangeCallback?.invoke()
        return super.onPreferenceTreeClick(preference)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.music_preferences, rootKey)

        // Hide EVERYTHING first except music source
        hideAllPreferencesExceptSource()

        // Set initial music source to Spotify
        currentMusicSource = MUSIC_SOURCE_SPOTIFY

        // Get the music source from preferences if it exists
        preferenceManager.sharedPreferences?.getString("music_source", MUSIC_SOURCE_SPOTIFY)?.let { savedSource ->
            currentMusicSource = savedSource
        }

        // Force initial visibility update based on current source
        updateVisiblePreferences(currentMusicSource)

        findPreference<ListPreference>("music_source")?.apply {
            value = currentMusicSource  // Set the current selection

            setOnPreferenceChangeListener { _, newValue ->
                val newSource = newValue.toString()
                Timber.d("Switching music source from $currentMusicSource to $newSource")

                // Important: Disable ALL sources first and clean up
                when (currentMusicSource) {
                    MUSIC_SOURCE_SPOTIFY -> {
                        if (spotifyPreferences.isEnabled()) {
                            spotifyPreferences.setEnabled(false)
                            spotifyPreferences.setConnectionState(false)
                            spotifyManager.disconnect()
                            findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked = false
                        }
                    }
                    MUSIC_SOURCE_RADIO -> {
                        if (radioPreferences.isEnabled()) {
                            radioPreferences.setEnabled(false)
                            radioManager.disconnect()
                            findPreference<SwitchPreferenceCompat>("radio_enabled")?.isChecked = false
                        }
                    }
                }

                // Update current source AFTER disabling previous source
                currentMusicSource = newSource

                // Update UI visibility immediately
                when (newSource) {
                    MUSIC_SOURCE_SPOTIFY -> {
                        // Hide all radio options
                        findPreference<SwitchPreferenceCompat>("radio_enabled")?.isVisible = false
                        findPreference<Preference>("radio_station_search")?.isVisible = false
                        findPreference<Preference>("radio_favorites")?.isVisible = false
                        findPreference<Preference>("radio_recent")?.isVisible = false
                        // Hide local music
                        findPreference<Preference>("local_music_folder")?.isVisible = false

                        // Show Spotify switch but hide other options until enabled
                        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.apply {
                            isVisible = true
                            isChecked = false
                        }
                        findPreference<Preference>("spotify_login")?.isVisible = false
                        findPreference<Preference>("spotify_playlist")?.isVisible = false
                        findPreference<Preference>("spotify_autoplay")?.isVisible = false
                    }
                    MUSIC_SOURCE_RADIO -> {
                        // Hide all Spotify options
                        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isVisible = false
                        findPreference<Preference>("spotify_login")?.isVisible = false
                        findPreference<Preference>("spotify_playlist")?.isVisible = false
                        findPreference<Preference>("spotify_shuffle")?.isVisible = false  // Add this line
                        findPreference<Preference>("spotify_autoplay")?.isVisible = false
                        // Hide local music
                        findPreference<Preference>("local_music_folder")?.isVisible = false

                        // Show radio switch but hide other options until enabled
                        findPreference<SwitchPreferenceCompat>("radio_enabled")?.apply {
                            isVisible = true
                            isChecked = false
                        }
                        findPreference<Preference>("radio_station_search")?.isVisible = false
                        findPreference<Preference>("radio_favorites")?.isVisible = false
                        findPreference<Preference>("radio_recent")?.isVisible = false
                    }
                    MUSIC_SOURCE_LOCAL -> {
                        // Hide all Spotify options
                        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isVisible = false
                        findPreference<Preference>("spotify_login")?.isVisible = false
                        findPreference<Preference>("spotify_playlist")?.isVisible = false
                        findPreference<Preference>("spotify_shuffle")?.isVisible = false  // Add this line
                        findPreference<Preference>("spotify_autoplay")?.isVisible = false
                        // Hide all radio options
                        findPreference<SwitchPreferenceCompat>("radio_enabled")?.isVisible = false
                        findPreference<Preference>("radio_station_search")?.isVisible = false
                        findPreference<Preference>("radio_favorites")?.isVisible = false
                        findPreference<Preference>("radio_recent")?.isVisible = false
                        // Show local music
                        findPreference<Preference>("local_music_folder")?.isVisible = true
                    }
                }

                // Force immediate widget update
                widgetManager.updateMusicWidgetBasedOnSource()

                // Force preference screen to redraw
                preferenceScreen.notifyDependencyChange(false)

                // Notify about layout changes for dialog resizing
                onPreferenceChangeCallback?.invoke()

                true
            }
        }

        // Setup Spotify enable/disable listener
        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            findPreference<Preference>("spotify_login")?.isVisible = enabled
            findPreference<Preference>("spotify_playlist")?.isVisible = enabled
            findPreference<Preference>("spotify_autoplay")?.isVisible = enabled
            findPreference<Preference>("spotify_shuffle")?.isVisible = enabled  // Add this line
            onPreferenceChangeCallback?.invoke()
            true
        }

        // Setup Radio enable/disable listener
        findPreference<SwitchPreferenceCompat>("radio_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            findPreference<Preference>("radio_station_search")?.isVisible = enabled
            findPreference<Preference>("radio_favorites")?.isVisible = enabled
            findPreference<Preference>("radio_recent")?.isVisible = enabled
            onPreferenceChangeCallback?.invoke()
            true
        }

        // Initialize preferences based on current source
        updateVisiblePreferences(currentMusicSource)
        setupRadioPreferences()
        setupLocalMusicPreferences()

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

    private fun hideAllPreferencesExceptSource() {
        // Hide Spotify preferences
        findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isVisible = false
        findPreference<Preference>("spotify_login")?.isVisible = false
        findPreference<Preference>("spotify_playlist")?.isVisible = false
        findPreference<SwitchPreferenceCompat>("spotify_shuffle")?.isVisible = false
        findPreference<SwitchPreferenceCompat>("spotify_autoplay")?.isVisible = false

        // Hide Radio preferences
        findPreference<SwitchPreferenceCompat>("radio_enabled")?.isVisible = false
        findPreference<Preference>("radio_station_search")?.isVisible = false
        findPreference<Preference>("radio_favorites")?.isVisible = false
        findPreference<Preference>("radio_recent")?.isVisible = false

        // Hide Local Music preferences
        findPreference<Preference>("local_music_folder")?.isVisible = false
    }

    private fun updateVisiblePreferences(musicSource: String) {
        Timber.d("Updating visible preferences for music source: $musicSource")

        // Hide ALL preferences first
        findPreference<Preference>("spotify_enabled")?.isVisible = false
        findPreference<Preference>("spotify_login")?.isVisible = false
        findPreference<Preference>("spotify_playlist")?.isVisible = false
        findPreference<Preference>("spotify_shuffle")?.isVisible = false  // Add this line
        findPreference<Preference>("spotify_autoplay")?.isVisible = false
        findPreference<Preference>("local_music_folder")?.isVisible = false
        findPreference<Preference>("radio_enabled")?.isVisible = false
        findPreference<Preference>("radio_station_search")?.isVisible = false
        findPreference<Preference>("radio_favorites")?.isVisible = false
        findPreference<Preference>("radio_recent")?.isVisible = false

        // Show only the relevant ones based on selected source
        when (musicSource) {
            MUSIC_SOURCE_SPOTIFY -> {
                findPreference<SwitchPreferenceCompat>("spotify_enabled")?.apply {
                    isVisible = true
                    // Only show options if Spotify is enabled
                    val showSpotifyOptions = isChecked
                    findPreference<Preference>("spotify_login")?.isVisible = showSpotifyOptions
                    findPreference<Preference>("spotify_playlist")?.isVisible = showSpotifyOptions
                    findPreference<Preference>("spotify_shuffle")?.isVisible = showSpotifyOptions  // Add this line
                    findPreference<Preference>("spotify_autoplay")?.isVisible = showSpotifyOptions
                }
            }
            MUSIC_SOURCE_RADIO -> {
                findPreference<SwitchPreferenceCompat>("radio_enabled")?.apply {
                    isVisible = true
                    // Only show options if radio is enabled
                    val showRadioOptions = isChecked
                    findPreference<Preference>("radio_station_search")?.isVisible = showRadioOptions
                    findPreference<Preference>("radio_favorites")?.isVisible = showRadioOptions
                    findPreference<Preference>("radio_recent")?.isVisible = showRadioOptions
                }
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
                            // Let the auth process handle the widget update
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
                    // Properly handle disabling Spotify
                    spotifyPreferences.setEnabled(false)
                    spotifyPreferences.setConnectionState(false)
                    spotifyManager.disconnect()

                    // Hide Spotify options
                    findPreference<Preference>("spotify_login")?.isVisible = false
                    findPreference<Preference>("spotify_playlist")?.isVisible = false
                    findPreference<SwitchPreferenceCompat>("spotify_shuffle")?.isVisible = false
                    findPreference<SwitchPreferenceCompat>("spotify_autoplay")?.isVisible = false

                    // Force widget update
                    widgetManager.updateMusicWidgetBasedOnSource()

                    // Force preference screen to redraw
                    preferenceScreen.notifyDependencyChange(false)

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

        findPreference<SwitchPreferenceCompat>("spotify_shuffle")?.apply {
            isChecked = spotifyPreferences.isShuffleEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val shuffleEnabled = newValue as Boolean
                spotifyPreferences.setShuffleEnabled(shuffleEnabled)

                // Apply shuffle setting immediately if connected
                if (spotifyManager.connectionState.value is SpotifyManager.ConnectionState.Connected) {
                    spotifyManager.setShuffleMode(shuffleEnabled)
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
                    activity?.runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Please enable and connect to Spotify first",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@setOnPreferenceClickListener true
                }

                if (spotifyManager.connectionState.value !is SpotifyManager.ConnectionState.Connected) {
                    activity?.runOnUiThread {
                        Toast.makeText(
                            requireContext(),
                            "Connecting to Spotify...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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
                        activity?.runOnUiThread {
                            loadingDialog.dismiss()
                            Timber.d("Available playlists: ${playlists.map { "${it.title} (${it.uri})" }}")

                            // Create a custom adapter for the dialog
                            val adapter = object : BaseAdapter() {
                                override fun getCount(): Int = playlists.size
                                override fun getItem(position: Int): Any = playlists[position]
                                override fun getItemId(position: Int): Long = position.toLong()

                                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                                    val view = convertView ?: LayoutInflater.from(parent.context)
                                        .inflate(R.layout.item_playlist, parent, false)

                                    val playlist = playlists[position]
                                    val titleView = view.findViewById<TextView>(R.id.playlist_title)
                                    val coverView = view.findViewById<ImageView>(R.id.playlist_cover)

                                    titleView.text = playlist.title

                                    // Check specifically for Liked Songs
                                    if (playlist.uri.contains(":saved:tracks")) {
                                        Timber.d("Found Liked Songs playlist: ${playlist.title} - Setting custom gradient icon")
                                        activity?.runOnUiThread {
                                            coverView.setImageResource(R.drawable.ic_spotify_liked_songs)
                                        }
                                    } else {
                                        Timber.d("Regular playlist: ${playlist.title} - Loading cover image")
                                        spotifyManager.getPlaylistCover(playlist) { bitmap ->
                                            activity?.runOnUiThread {
                                                if (bitmap != null) {
                                                    coverView.setImageBitmap(bitmap)
                                                } else {
                                                    coverView.setImageResource(R.drawable.ic_spotify_logo)
                                                }
                                            }
                                        }
                                    }

                                    return view
                                }
                            }

                            val dialog = MaterialAlertDialogBuilder(requireContext())
                                .setTitle("Select Playlist")
                                .setAdapter(adapter) { _, which ->
                                    val selectedPlaylist = playlists[which]
                                    Timber.d("Selected playlist: ${selectedPlaylist.title} with URI: ${selectedPlaylist.uri}")

                                    // Save playlist selection
                                    spotifyPreferences.setSelectedPlaylistWithTitle(selectedPlaylist.uri, selectedPlaylist.title)
                                    summary = selectedPlaylist.title

                                    // First pause current playback
                                    spotifyManager.pause()

                                    // Play the playlist directly - let SpotifyManager handle the section logic
                                    spotifyManager.playPlaylist(selectedPlaylist.uri)

                                    activity?.runOnUiThread {
                                        Toast.makeText(
                                            requireContext(),
                                            "Selected: ${selectedPlaylist.title}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .create()

                            dialog.show()
                        }
                    },
                    errorCallback = { error ->
                        activity?.runOnUiThread {
                            loadingDialog.dismiss()
                            Timber.e(error, "Failed to load playlists")

                            val errorMessage = when {
                                error is IOException && error.message?.contains("401") == true -> {
                                    // Token expired, try to refresh
                                    spotifyManager.checkAndRefreshTokenIfNeeded()
                                    "Your Spotify session has expired. Please reconnect to Spotify."
                                }
                                error is IOException ->
                                    "Connection error. Please check your internet connection."
                                else ->
                                    "Error loading playlists: ${error.message}"
                            }

                            Toast.makeText(
                                requireContext(),
                                errorMessage,
                                Toast.LENGTH_LONG
                            ).show()
                        }
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
                    lifecycleScope.launch(Dispatchers.Main) {
                        spotifyManager.getPlaylistInfo(
                            uri = uri,
                            callback = { playlist ->
                                val summary = playlist?.title ?: "Select playlist"
                                spotifyPreferences.setPlaylistSummary(summary)  // Save the summary
                                findPreference<Preference>("spotify_playlist")?.setSummary(summary)
                            },
                            errorCallback = { error ->
                                activity?.runOnUiThread {
                                    findPreference<Preference>("spotify_playlist")?.setSummary("Select playlist")
                                }
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
        findPreference<SwitchPreferenceCompat>("radio_enabled")?.apply {
            isChecked = radioPreferences.isEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                radioPreferences.setEnabled(enabled)

                // Update visibility immediately
                findPreference<Preference>("radio_station_search")?.isVisible = enabled
                findPreference<Preference>("radio_favorites")?.isVisible = enabled
                findPreference<Preference>("radio_recent")?.isVisible = enabled

                if (!enabled) {
                    radioManager.disconnect()
                } else {
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
            .setPositiveButton("Close", null)  // Add close button
            .create()

        val searchInput = dialogView.findViewById<TextInputEditText>(R.id.search_input)
        val searchButton = dialogView.findViewById<MaterialButton>(R.id.search_button)
        val stationsList = dialogView.findViewById<RecyclerView>(R.id.stations_list)
        val loadingIndicator = dialogView.findViewById<ProgressBar>(R.id.loading_indicator)
        val noResultsText = dialogView.findViewById<TextView>(R.id.no_results_text)

        lateinit var stationsAdapter: RadioStationAdapter
        var currentJob: Job? = null

        fun performSearch() {
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
            } else {
                Toast.makeText(requireContext(), "Please enter at least 3 characters", Toast.LENGTH_SHORT).show()
            }
        }

        stationsAdapter = RadioStationAdapter(
            radioManager = radioManager,
            onStationClick = { station ->
                currentJob?.cancel()
                stationsAdapter.setLoadingState(station.id)

                currentJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        var hasStartedLoading = false

                        // Play station first
                        radioManager.playStation(station)

                        // Then collect states
                        radioManager.playbackState
                            .onEach { state ->
                                when (state) {
                                    is RadioManager.PlaybackState.Playing -> {
                                        stationsAdapter.setLoadingState(null)
                                        radioPreferences.setLastStation(station)
                                        radioPreferences.addToRecentStations(station)
                                        dialog.dismiss()
                                    }
                                    RadioManager.PlaybackState.Loading -> {
                                        hasStartedLoading = true
                                        stationsAdapter.setLoadingState(station.id)
                                    }
                                    RadioManager.PlaybackState.Idle -> {
                                        // Only show error if we've already started loading
                                        if (hasStartedLoading) {
                                            stationsAdapter.setLoadingState(null)
                                            Toast.makeText(requireContext(), "Failed to load station", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                            .collect()
                    } catch (e: Exception) {
                        stationsAdapter.setLoadingState(null)
                        Timber.e(e, "Error playing station")
                    }
                }
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

        // Search button click listener
        searchButton.setOnClickListener {
            performSearch()
        }

        // Enter key listener
        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                performSearch()
                true
            } else false
        }

        dialog.setOnDismissListener {
            currentJob?.cancel()
        }

        dialog.show()
    }

    private fun showRecentStationsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_radio_stations_list, null)

        val stationsList = dialogView.findViewById<RecyclerView>(R.id.stations_list)

        val alertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Recent Stations")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        var currentJob: Job? = null
        lateinit var recentAdapter: RadioStationAdapter

        recentAdapter = RadioStationAdapter(
            radioManager = radioManager,
            onStationClick = { station ->
                currentJob?.cancel()
                recentAdapter.setLoadingState(station.id)

                currentJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        radioManager.playStation(station)
                        radioManager.playbackState
                            .onEach { state ->
                                when (state) {
                                    is RadioManager.PlaybackState.Playing -> {
                                        recentAdapter.setLoadingState(null)
                                        radioPreferences.setLastStation(station)
                                        radioPreferences.addToRecentStations(station)
                                        alertDialog.dismiss()
                                    }
                                    RadioManager.PlaybackState.Loading -> {
                                        recentAdapter.setLoadingState(station.id)
                                    }
                                    RadioManager.PlaybackState.Idle -> {
                                        recentAdapter.setLoadingState(null)
                                        Toast.makeText(requireContext(), "Failed to load station", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.collect() // Add .collect() here
                    } catch (e: Exception) {
                        recentAdapter.setLoadingState(null)
                        Timber.e(e, "Error playing station")
                    }
                }
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

        alertDialog.setOnDismissListener {
            currentJob?.cancel()
        }

        alertDialog.show()

        val recentStations = radioPreferences.getRecentStations()
        if (recentStations.isEmpty()) {
            Toast.makeText(requireContext(), "No recent stations", Toast.LENGTH_SHORT).show()
            alertDialog.dismiss()
        } else {
            recentAdapter.submitList(recentStations)
        }
    }

    private fun showFavoritesDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_radio_favorites, null)

        val stationsList = dialogView.findViewById<RecyclerView>(R.id.stations_list)

        val alertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Favorite Stations")
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .create()

        var currentJob: Job? = null
        lateinit var favoritesAdapter: RadioStationAdapter

        favoritesAdapter = RadioStationAdapter(
            radioManager = radioManager,
            onStationClick = { station ->
                currentJob?.cancel()
                favoritesAdapter.setLoadingState(station.id)

                currentJob = viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        radioManager.playStation(station)
                        radioManager.playbackState
                            .onEach { state ->
                                when (state) {
                                    is RadioManager.PlaybackState.Playing -> {
                                        favoritesAdapter.setLoadingState(null)
                                        radioPreferences.setLastStation(station)
                                        alertDialog.dismiss()
                                    }
                                    RadioManager.PlaybackState.Loading -> {
                                        favoritesAdapter.setLoadingState(station.id)
                                    }
                                    RadioManager.PlaybackState.Idle -> {
                                        favoritesAdapter.setLoadingState(null)
                                        Toast.makeText(requireContext(), "Failed to load station", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.collect() // Add .collect() here
                    } catch (e: Exception) {
                        favoritesAdapter.setLoadingState(null)
                        Timber.e(e, "Error playing station")
                    }
                }
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

        alertDialog.setOnDismissListener {
            currentJob?.cancel()
        }

        alertDialog.show()
        favoritesAdapter.submitList(radioPreferences.getFavoriteStations())
    }

    fun applyChanges() {
        Timber.d("Applying music source changes")
    }

    fun cancelChanges() {
        Timber.d("Canceling music source changes")
    }

    override fun onResume() {
        super.onResume()
        spotifyManager.checkAndRefreshTokenIfNeeded()
    }

    class LocalMusicAdapter(
        private val onTrackClick: (LocalMusicManager.LocalTrack) -> Unit
    ) : ListAdapter<LocalMusicManager.LocalTrack, LocalMusicAdapter.TrackViewHolder>(
        object : DiffUtil.ItemCallback<LocalMusicManager.LocalTrack>() {
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
    ) {

        class TrackViewHolder(val binding: ItemLocalTrackBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
            val binding = ItemLocalTrackBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return TrackViewHolder(binding)
        }

        override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
            val track = getItem(position)
            holder.binding.apply {
                trackTitle.text = track.title
                artistName.text = track.artist
                albumName.text = track.album

                // Format duration
                val minutes = TimeUnit.MILLISECONDS.toMinutes(track.duration)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(track.duration) % 60
                trackDuration.text = String.format("%d:%02d", minutes, seconds)

                // Load album art if available
                track.albumArtPath?.let { path ->
                    val bitmap = BitmapFactory.decodeFile(path)
                    if (bitmap != null) {
                        albumArt.setImageBitmap(bitmap)
                        albumArt.visibility = View.VISIBLE
                    } else {
                        albumArt.setImageResource(R.drawable.ic_music_note)
                    }
                } ?: run {
                    albumArt.setImageResource(R.drawable.ic_music_note)
                }

                // Click listener
                root.setOnClickListener {
                    onTrackClick(track)
                }
            }
        }
    }

    class LocalMusicTrackAdapter(
        private val onTrackClick: (LocalMusicManager.LocalTrack) -> Unit
    ) : RecyclerView.Adapter<LocalMusicTrackAdapter.TrackViewHolder>() {

        private var tracks: List<LocalMusicManager.LocalTrack> = emptyList()

        fun submitList(newTracks: List<LocalMusicManager.LocalTrack>) {
            tracks = newTracks
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = tracks.size

        class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val titleText: TextView = itemView.findViewById(R.id.track_title)
            val artistText: TextView = itemView.findViewById(R.id.artist_name)
            val albumText: TextView = itemView.findViewById(R.id.album_name)
            val durationText: TextView = itemView.findViewById(R.id.track_duration)
            val albumArt: ImageView = itemView.findViewById(R.id.album_art)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_local_track, parent, false)
            return TrackViewHolder(view)
        }

        override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
            val track = tracks[position]

            holder.titleText.text = track.title
            holder.artistText.text = track.artist
            holder.albumText.text = track.album

            // Format duration
            val minutes = TimeUnit.MILLISECONDS.toMinutes(track.duration)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(track.duration) % 60
            holder.durationText.text = String.format("%d:%02d", minutes, seconds)

            // Load album art if available
            track.albumArtPath?.let { path ->
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    holder.albumArt.setImageBitmap(bitmap)
                    holder.albumArt.visibility = View.VISIBLE
                } else {
                    holder.albumArt.setImageResource(R.drawable.ic_music_note)
                }
            } ?: run {
                holder.albumArt.setImageResource(R.drawable.ic_music_note)
            }

            // Click listener
            holder.itemView.setOnClickListener {
                onTrackClick(track)
            }
        }
    }
}