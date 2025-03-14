package com.example.screensaver.music

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    private var currentMusicSource: String = MUSIC_SOURCE_SPOTIFY

    private val spotifyAuthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        spotifyAuthManager.handleAuthResponse(
            requestCode = SpotifyAuthManager.REQUEST_CODE,
            resultCode = result.resultCode,
            data = result.data
        )
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.music_preferences, rootKey)

        findPreference<ListPreference>("music_source")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                updateVisiblePreferences(newValue.toString())
                true
            }
        }

        setupSpotifyPreferences()

        // Initialize the visibility based on current selection
        updateVisiblePreferences(currentMusicSource)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe auth state changes
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
                }
            }
        }

        // Observe connection state changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                spotifyManager.connectionState.collect { state ->
                    updateSpotifyLoginSummary()
                }
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

        setupSpotifyPlaylistPreference()
    }

    private fun setupSpotifyPlaylistPreference() {
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
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Select Playlist")
                            .setItems(playlists.map { it.title }.toTypedArray()) { _, which ->
                                val selectedPlaylist = playlists[which]
                                spotifyPreferences.setSelectedPlaylist(selectedPlaylist.uri)
                                spotifyPreferences.setPlaylistSummary(selectedPlaylist.title)
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
                        Toast.makeText(
                            requireContext(),
                            "Error loading playlists: ${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
                true
            }

            // Set initial summary
            spotifyPreferences.getPlaylistSummary()?.let { savedSummary ->
                summary = savedSummary
            } ?: run {
                summary = "Select playlist"
            }
        }
    }

    private fun updateVisiblePreferences(musicSource: String) {
        // First hide all preferences
        findPreference<Preference>("spotify_enabled")?.isVisible = false
        findPreference<Preference>("spotify_login")?.isVisible = false
        findPreference<Preference>("spotify_playlist")?.isVisible = false
        findPreference<Preference>("spotify_autoplay")?.isVisible = false
        findPreference<Preference>("local_music_folder")?.isVisible = false
        findPreference<Preference>("radio_url")?.isVisible = false

        // Then show only the relevant ones based on selected source
        when (musicSource) {
            MUSIC_SOURCE_SPOTIFY -> {
                findPreference<Preference>("spotify_enabled")?.isVisible = true
                // Only show these if spotify is enabled
                if (findPreference<SwitchPreferenceCompat>("spotify_enabled")?.isChecked == true) {
                    findPreference<Preference>("spotify_login")?.isVisible = true
                    findPreference<Preference>("spotify_playlist")?.isVisible = true
                    findPreference<Preference>("spotify_autoplay")?.isVisible = true
                }
            }
            MUSIC_SOURCE_LOCAL -> {
                findPreference<Preference>("local_music_folder")?.isVisible = true
            }
            MUSIC_SOURCE_RADIO -> {
                findPreference<Preference>("radio_url")?.isVisible = true
            }
        }

        currentMusicSource = musicSource
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
                    spotifyManager.getCurrentUser(
                        callback = { user ->
                            Handler(Looper.getMainLooper()).post {
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
        }
    }

    fun applyChanges() {
        Log.d(TAG, "Applying music source changes")
    }

    fun cancelChanges() {
        Log.d(TAG, "Canceling music source changes")
    }
}