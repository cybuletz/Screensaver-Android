package com.example.screensaver.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.AlbumSelectionActivity
import com.example.screensaver.R
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.localphotos.LocalPhotoSelectionActivity
import com.example.screensaver.models.MediaItem
import com.example.screensaver.PhotoRepository
import com.example.screensaver.PhotoRepository.PhotoAddMode
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.AppPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.content.Context
import com.example.screensaver.photos.PhotoManagerActivity

@AndroidEntryPoint
class PhotoSourcesPreferencesFragment : PreferenceFragmentCompat() {

    interface PhotoSourcesListener {
        fun onPhotosAdded(isFirstTime: Boolean)
        fun onSourceSelectionComplete()
    }

    @Inject
    lateinit var appDataManager: AppDataManager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var photoManager: PhotoRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    private var googleSignInClient: GoogleSignInClient? = null
    private var pendingChanges = mutableMapOf<String, Any>()
    private var listener: PhotoSourcesListener? = null
    private var isApplyingChanges = false
    private var isSourceSelectionComplete = false

    companion object {
        private const val TAG = "PhotoSourcesPrefFragment"
        private const val REQUEST_SELECT_PHOTOS = 1001
        private const val PERMISSION_REQUEST_CODE = 100
        private const val SOURCE_LOCAL_PHOTOS = "local"
        private const val SOURCE_GOOGLE_PHOTOS = "google_photos"
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                handleSignInResult(GoogleSignIn.getSignedInAccountFromIntent(result.data))
            }
            Activity.RESULT_CANCELED -> {
                findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = false
                updateGooglePhotosState(false)
            }
            else -> {
                Log.e(TAG, "Sign in failed with result code: ${result.resultCode}")
                findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = false
                updateGooglePhotosState(false)
                showError(getString(R.string.sign_in_failed))
            }
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.READ_EXTERNAL_STORAGE, false) -> {
                launchLocalPhotoSelection()
            }
            permissions.getOrDefault(Manifest.permission.READ_MEDIA_IMAGES, false) -> {
                launchLocalPhotoSelection()
            }
            else -> {
                showError(getString(R.string.error_storage_permission))
            }
        }
    }

    fun resetPhotoPickingSession() {
        Log.d(TAG, "Resetting photo picking session")

        view?.post {
            try {
                // Reset source toggles while maintaining Google auth
                findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.apply {
                    isChecked = false
                    callChangeListener(false)
                }

                findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
                    isChecked = false
                    callChangeListener(false)
                }

                // Clear source selections but maintain Google auth state
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putStringSet("photo_source_selection", emptySet())
                    .apply()

                // Reset dialog flag in activity when sources are reset
                (activity as? PhotoManagerActivity)?.resetDialogShownFlag()

                // Clear pending changes
                pendingChanges.clear()

                // Update UI state
                updateSourceSelectionVisibility(photoManager.getAllPhotos().isNotEmpty())

                Log.d(TAG, "Photo picking session reset complete")
            } catch (e: Exception) {
                Log.e(TAG, "Error resetting photo picking session", e)
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? PhotoSourcesListener
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.photo_sources_preferences, rootKey)

        // Setup local photos switch
        findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    // Only check permissions when enabling local photos
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), PERMISSION_REQUEST_CODE)
                            return@setOnPreferenceChangeListener false
                        }
                    } else {
                        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
                            return@setOnPreferenceChangeListener false
                        }
                    }
                }
                true
            }
        }

        setupPreferences()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, enable the switch
                    findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.isChecked = true
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGoogleSignIn()
        restoreState()
        updateSourceSelectionVisibility(photoManager.getAllPhotos().isNotEmpty())
    }

    private fun updateSourceSelectionVisibility(hasPhotos: Boolean) {
        // Always keep sources enabled
        findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.isEnabled = true
        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isEnabled = true
        findPreference<Preference>("select_local_photos")?.isEnabled = isLocalPhotosEnabled()
        findPreference<Preference>("select_google_albums")?.isEnabled = isGooglePhotosEnabled()
    }

    private fun setupPreferences() {
        // Local Photos Setup
        findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                handleLocalPhotosStateChange(enabled)
                true
            }
        }

        findPreference<Preference>("select_local_photos")?.apply {
            setOnPreferenceClickListener {
                checkAndRequestStoragePermission()
                true
            }
            isEnabled = isLocalPhotosEnabled()
        }

        // Google Photos Setup
        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    initiateGoogleSignIn()
                    false // Don't update until sign-in completes
                } else {
                    handleGooglePhotosStateChange(false)
                    true
                }
            }
        }

        findPreference<Preference>("select_google_albums")?.apply {
            setOnPreferenceClickListener {
                launchGoogleAlbumSelection()
                true
            }
            isEnabled = isGooglePhotosEnabled()
        }
    }

    private fun handleLocalPhotosStateChange(enabled: Boolean) {
        val currentSources = getCurrentPhotoSources().toMutableSet()
        if (enabled) {
            currentSources.add(SOURCE_LOCAL_PHOTOS)
        } else {
            currentSources.remove(SOURCE_LOCAL_PHOTOS)
        }
        pendingChanges["photo_sources"] = currentSources
        findPreference<Preference>("select_local_photos")?.isEnabled = enabled
    }

    private fun handleGooglePhotosStateChange(enabled: Boolean) {
        val currentSources = getCurrentPhotoSources().toMutableSet()
        if (enabled) {
            currentSources.add(SOURCE_GOOGLE_PHOTOS)
        } else {
            currentSources.remove(SOURCE_GOOGLE_PHOTOS)
            signOutGoogle()
        }
        pendingChanges["photo_sources"] = currentSources
        pendingChanges["google_photos_enabled"] = enabled
        updateGooglePhotosState(enabled)
    }

    private fun getCurrentPhotoSources(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", emptySet()) ?: emptySet()
    }

    private fun isLocalPhotosEnabled(): Boolean {
        return getCurrentPhotoSources().contains(SOURCE_LOCAL_PHOTOS)
    }

    private fun isGooglePhotosEnabled(): Boolean {
        return getCurrentPhotoSources().contains(SOURCE_GOOGLE_PHOTOS)
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchLocalPhotoSelection()
            } else {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES))
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchLocalPhotoSelection()
            } else {
                storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    private fun launchLocalPhotoSelection() {
        val intent = Intent(requireContext(), LocalPhotoSelectionActivity::class.java).apply {
            val selectedPhotos = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getStringSet("selected_local_photos", emptySet())
            putExtra("selected_photos", ArrayList(selectedPhotos ?: emptySet()))
        }
        startActivityForResult(intent, REQUEST_SELECT_PHOTOS)
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
            .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
            .requestIdToken(getString(R.string.google_oauth_client_id))
            .build()

        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
    }

    private fun initiateGoogleSignIn() {
        try {
            googleSignInClient?.signOut()?.addOnCompleteListener {
                signInLauncher.launch(googleSignInClient?.signInIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating Google Sign-in", e)
            showError(getString(R.string.sign_in_failed))
        }
    }

    private fun handleSignInResult(completedTask: com.google.android.gms.tasks.Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            account?.serverAuthCode?.let { authCode ->
                account.email?.let { email ->
                    exchangeAuthCode(authCode, email)
                }
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed", e)
            updateGooglePhotosState(false)
            showError(getString(R.string.sign_in_failed))
        }
    }

    private fun exchangeAuthCode(authCode: String, email: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tokenEndpoint = URL("https://oauth2.googleapis.com/token")
                val connection = (tokenEndpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                val postData = buildString {
                    append("code=").append(URLEncoder.encode(authCode, "UTF-8"))
                    append("&client_id=").append(URLEncoder.encode(getString(R.string.google_oauth_client_id), "UTF-8"))
                    append("&client_secret=").append(URLEncoder.encode(getString(R.string.google_oauth_client_secret), "UTF-8"))
                    append("&redirect_uri=").append(URLEncoder.encode("http://localhost", "UTF-8"))
                    append("&grant_type=authorization_code")
                }

                connection.outputStream.use { it.write(postData.toByteArray()) }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                val accessToken = jsonResponse.getString("access_token")
                val refreshToken = jsonResponse.getString("refresh_token")
                val expiresIn = jsonResponse.getLong("expires_in")
                val expirationTime = System.currentTimeMillis() + (expiresIn * 1000)

                withContext(Dispatchers.Main) {
                    secureStorage.saveGoogleCredentials(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expirationTime = expirationTime,
                        email = email
                    )
                    handleGooglePhotosStateChange(true)
                    findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = true
                    applyChanges()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code", e)
                withContext(Dispatchers.Main) {
                    updateGooglePhotosState(false)
                    showError(getString(R.string.sign_in_failed))
                }
            }
        }
    }

    private fun signOutGoogle() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            secureStorage.clearGoogleCredentials()
            updateGooglePhotosState(false)
        }
    }

    private fun showSignInPrompt() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sign_in_required)
            .setMessage(R.string.google_photos_sign_in_required)
            .setPositiveButton(R.string.sign_in) { _, _ ->
                // Instead of performClick, directly initiate Google Sign-in
                initiateGoogleSignIn()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchGoogleAlbumSelection() {
        if (GoogleSignIn.getLastSignedInAccount(requireContext()) == null ||
            secureStorage.getGoogleCredentials() == null) {
            showSignInPrompt()
            return
        }

        lifecycleScope.launch {
            try {
                if (!googlePhotosManager.initialize()) {
                    showError(getString(R.string.google_photos_init_failed))
                } else {
                    startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Google Photos", e)
                showError(getString(R.string.google_photos_init_failed))
            }
        }
    }

    private fun updateGooglePhotosState(enabled: Boolean) {
        findPreference<Preference>("select_google_albums")?.isEnabled = enabled
    }

    private fun restoreState() {
        val currentSources = getCurrentPhotoSources()

        findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.isChecked =
            currentSources.contains(SOURCE_LOCAL_PHOTOS)

        val hasGoogleCredentials = secureStorage.getGoogleCredentials() != null
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val hasRequiredScope = account?.grantedScopes?.contains(
            Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
        ) == true

        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked =
            currentSources.contains(SOURCE_GOOGLE_PHOTOS) && hasGoogleCredentials && hasRequiredScope

        updateGooglePhotosState(hasGoogleCredentials && hasRequiredScope)
    }

    private fun showError(message: String) {
        view?.let { v ->
            Snackbar.make(v, message, Snackbar.LENGTH_LONG).show()
        }
    }

    fun cancelChanges() {
        pendingChanges.clear()
        restoreState()
    }

    fun applyChanges() {
        // Guard against recursive calls
        if (isApplyingChanges) return

        isApplyingChanges = true
        try {
            pendingChanges.forEach { (key, value) ->
                when (key) {
                    "photo_sources" -> {
                        @Suppress("UNCHECKED_CAST")
                        val sources = value as Set<String>
                        PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .edit()
                            .putStringSet("photo_source_selection", sources)
                            .apply()
                        appDataManager.updateState { it.copy(photoSources = sources) }
                    }
                    "google_photos_enabled" -> {
                        val enabled = value as Boolean
                        appDataManager.updateState { it.copy(googlePhotosEnabled = enabled) }
                    }
                }
            }
            pendingChanges.clear()

            if (!isSourceSelectionComplete && photoManager.getAllPhotos().isNotEmpty()) {
                isSourceSelectionComplete = true
                notifyPhotosAdded()
                listener?.onSourceSelectionComplete()
            }
        } finally {
            isApplyingChanges = false
            isSourceSelectionComplete = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SELECT_PHOTOS && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra("selected_photos")?.let { selectedPhotos ->
                Log.d(TAG, "Selected photos count: ${selectedPhotos.size}")

                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putStringSet("selected_local_photos", selectedPhotos.toSet())
                    .apply()

                val mediaItems = selectedPhotos.map { uri ->
                    MediaItem(
                        id = uri,
                        albumId = "local_picked",
                        baseUrl = uri,
                        mimeType = "image/*",
                        width = 0,
                        height = 0,
                        description = null,
                        createdAt = System.currentTimeMillis(),
                        loadState = MediaItem.LoadState.IDLE
                    )
                }

                Log.d(TAG, "Adding ${mediaItems.size} photos to repository")
                photoManager.addPhotos(mediaItems, PhotoAddMode.APPEND)
                Log.d(TAG, "Current photo count: ${photoManager.getAllPhotos().size}")

                appDataManager.updateState { currentState ->
                    currentState.copy(
                        photoSources = currentState.photoSources + SOURCE_LOCAL_PHOTOS,
                        lastSyncTimestamp = System.currentTimeMillis()
                    )
                }

                findPreference<Preference>("select_local_photos")?.summary =
                    getString(R.string.photos_selected, selectedPhotos.size)

                // Apply changes and notify activity
                applyChanges()
                notifyPhotosAdded()
            }
        }
    }

    private fun notifyPhotosAdded() {
        val photoCount = photoManager.getAllPhotos().size
        Log.d(TAG, "Notifying activity of photos added. Current count: $photoCount")
        val isFirstTime = appPreferences.getBoolean("is_first_time_setup", true)
        listener?.onPhotosAdded(isFirstTime)
    }

    override fun onDestroy() {
        googleSignInClient = null
        super.onDestroy()
    }
}