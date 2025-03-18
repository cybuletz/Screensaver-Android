package com.example.screensaver.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import android.net.Uri
import com.example.screensaver.photos.PhotoManagerActivity
import android.os.Bundle
import android.provider.MediaStore
import androidx.fragment.app.DialogFragment

@AndroidEntryPoint
class PhotoSourcesPreferencesFragment : PreferenceFragmentCompat() {

    interface PhotoSourcesListener {
        fun onPhotosAdded(isFirstTime: Boolean)
        fun onSourceSelectionComplete()
    }

    @Inject
    lateinit var appDataManager: AppDataManager

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
        private const val GOOGLE_PHOTOS_REQUEST_CODE = 1002
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
        Log.d(TAG, "Launching Google Album Selection")
        if (GoogleSignIn.getLastSignedInAccount(requireContext()) == null ||
            secureStorage.getGoogleCredentials() == null) {
            Log.e(TAG, "No Google sign-in or credentials, showing sign-in prompt")
            showSignInPrompt()
            return
        }

        // Use the new photo picker for Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 100)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, GOOGLE_PHOTOS_REQUEST_CODE)
        } else {
            // For older versions, use ACTION_OPEN_DOCUMENT
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_LOCAL_ONLY, false)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, GOOGLE_PHOTOS_REQUEST_CODE)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Fragment onCreate, isDialog=${parentFragment is DialogFragment}")

        val fragmentTag = arguments?.getString("fragment_tag")
        Log.d(TAG, "Fragment created with tag: $fragmentTag")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, parent=${parentFragment?.javaClass?.simpleName}")
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            GOOGLE_PHOTOS_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Handling successful Google Photos result")
                    lifecycleScope.launch {
                        try {
                            // Get selected URIs
                            val selectedUris = mutableListOf<Uri>()
                            data?.let { intent ->
                                when {
                                    intent.clipData != null -> {
                                        val clipData = intent.clipData!!
                                        for (i in 0 until clipData.itemCount) {
                                            selectedUris.add(clipData.getItemAt(i).uri)
                                        }
                                    }
                                    intent.data != null -> {
                                        selectedUris.add(intent.data!!)
                                    }
                                    else -> {
                                        // Handle case where neither clipData nor data is present
                                        Log.w(TAG, "No data received from intent")
                                    }
                                }
                            }

                            if (selectedUris.isEmpty()) {
                                Log.w(TAG, "No URIs selected")
                                return@launch
                            }

                            // Take persistable permissions for each URI
                            selectedUris.forEach { uri ->
                                try {
                                    requireContext().contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error taking permission for URI: $uri", e)
                                }
                            }

                            // Create MediaItems
                            val mediaItems = selectedUris.map { uri ->
                                MediaItem(
                                    id = uri.toString(),
                                    albumId = "google_picked",
                                    baseUrl = uri.toString(),
                                    mimeType = "image/*",
                                    width = 0,
                                    height = 0,
                                    description = null,
                                    createdAt = System.currentTimeMillis(),
                                    loadState = MediaItem.LoadState.IDLE
                                )
                            }

                            // Add to repository
                            photoManager.addPhotos(mediaItems, PhotoAddMode.APPEND)

                            // Update sources and UI
                            val currentSources = getCurrentPhotoSources().toMutableSet()
                            currentSources.add(SOURCE_GOOGLE_PHOTOS)
                            pendingChanges["photo_sources"] = currentSources
                            pendingChanges["google_photos_enabled"] = true

                            withContext(Dispatchers.Main) {
                                findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = true
                                updateGooglePhotosState(true)
                            }

                            // Apply changes
                            applyChanges()
                            notifyPhotosAdded()

                            // Handle navigation
                            if (parentFragment is DialogFragment) {
                                Log.d(TAG, "In dialog, letting dialog handle navigation")
                            } else {
                                Log.d(TAG, "Not in dialog, handling navigation here")
                                activity?.apply {
                                    setResult(Activity.RESULT_OK)
                                    finish()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling Google Photos result", e)
                            showError(getString(R.string.save_error))
                        }
                    }
                } else {
                    Log.w(TAG, "Google Photos selection cancelled")
                    findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = false
                    updateGooglePhotosState(false)
                }
            }

            REQUEST_SELECT_PHOTOS -> {
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Local photos selection result received")
                    data?.getStringArrayListExtra("selected_photos")?.let { selectedPhotos ->
                        Log.d(TAG, "Processing ${selectedPhotos.size} selected local photos")

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

                        Log.d(TAG, "Adding ${mediaItems.size} local photos to repository")
                        photoManager.addPhotos(mediaItems, PhotoAddMode.APPEND)

                        // Update sources
                        val currentSources = getCurrentPhotoSources().toMutableSet()
                        currentSources.add(SOURCE_LOCAL_PHOTOS)
                        pendingChanges["photo_sources"] = currentSources

                        Log.d(TAG, "Updating UI and applying changes")
                        findPreference<Preference>("select_local_photos")?.summary =
                            getString(R.string.photos_selected, selectedPhotos.size)

                        // Apply changes and notify activity
                        applyChanges()
                        notifyPhotosAdded()
                    } ?: run {
                        Log.w(TAG, "No selected_photos in local photos result")
                    }
                }
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