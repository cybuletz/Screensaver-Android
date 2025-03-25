package com.example.screensaver.settings

import android.Manifest
import android.app.Activity
import android.content.ClipData
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.fragment.app.DialogFragment
import com.example.screensaver.photos.PersistentPhotoCache
import java.io.File

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

    private val persistentPhotoCache: PersistentPhotoCache
        get() = photoManager.persistentPhotoCache

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

    private val pickMultipleMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        if (uris.isNotEmpty()) {
            // Simulate an Intent result to reuse existing logic
            val clipData = ClipData.newRawUri(null, uris[0])
            for (i in 1 until uris.size) {
                clipData.addItem(ClipData.Item(uris[i]))
            }

            val resultIntent = Intent().apply {
                this.clipData = clipData
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            // Process the result using existing logic
            onActivityResult(GOOGLE_PHOTOS_REQUEST_CODE, Activity.RESULT_OK, resultIntent)
        }
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

    private fun launchGoogleAlbumSelection() {
        Log.d(TAG, "Launching Google Album Selection")

        // Add this Toast message at the beginning of the method
        Toast.makeText(
            requireContext(),
            "For stability, please select up to 50 photos at a time",
            Toast.LENGTH_LONG
        ).show()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)

                // Force Google Photos
                `package` = "com.google.android.apps.photos"

                // Add special extras for Android 11
                putExtra("android.provider.extra.INITIAL_URI",
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                putExtra("android.provider.extra.SHOW_ADVANCED", true)
                putExtra("android.provider.extra.SHOW_ALL_FILES", true)

                // For Android 11, we need these specific flags
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }

            try {
                // Try launching Google Photos directly first
                startActivityForResult(intent, GOOGLE_PHOTOS_REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "Direct launch failed, trying with chooser", e)
                try {
                    // If direct launch fails, try with chooser
                    val chooserIntent = Intent.createChooser(intent, getString(R.string.select_pictures))
                    startActivityForResult(chooserIntent, GOOGLE_PHOTOS_REQUEST_CODE)
                } catch (e: Exception) {
                    Log.e(TAG, "Chooser failed, falling back to legacy picker", e)
                    launchLegacyPicker()
                }
            }
        } else {
            launchLegacyPicker()
        }
    }

    private fun launchLegacyPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, GOOGLE_PHOTOS_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Legacy picker failed, using chooser", e)
            val chooserIntent = Intent.createChooser(intent, getString(R.string.select_pictures))
            startActivityForResult(chooserIntent, GOOGLE_PHOTOS_REQUEST_CODE)
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
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == GOOGLE_PHOTOS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                try {
                    val selectedUris = mutableListOf<Uri>()
                    val googlePhotosUris = mutableListOf<Uri>()

                    when {
                        data?.clipData != null -> {
                            val clipData = data.clipData!!
                            for (i in 0 until clipData.itemCount) {
                                clipData.getItemAt(i).uri?.let { uri ->
                                    // For Android 11, don't try to take persistable permissions for Google Photos URIs
                                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
                                        uri.toString().contains("com.google.android.apps.photos")) {
                                        // Just store the URI
                                        selectedUris.add(uri)
                                        googlePhotosUris.add(uri)
                                        // Store in current photo sources
                                        val currentSources = getCurrentPhotoSources().toMutableSet()
                                        currentSources.add(SOURCE_GOOGLE_PHOTOS)
                                        pendingChanges["photo_sources"] = currentSources
                                    } else {
                                        try {
                                            requireContext().contentResolver.takePersistableUriPermission(
                                                uri,
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            )
                                            selectedUris.add(uri)

                                            // If it's a Google Photos URI, add to list for caching
                                            if (uri.toString().contains("com.google.android.apps.photos") ||
                                                uri.toString().contains("googleusercontent.com")) {
                                                googlePhotosUris.add(uri)
                                            }
                                        } catch (e: SecurityException) {
                                            Log.e(TAG, "Failed to take permission for URI: $uri", e)
                                            // Still add the URI even if we couldn't get persistable permission
                                            selectedUris.add(uri)

                                            // If it's a Google Photos URI, add to list for caching
                                            if (uri.toString().contains("com.google.android.apps.photos") ||
                                                uri.toString().contains("googleusercontent.com")) {
                                                googlePhotosUris.add(uri)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        data?.data != null -> {
                            val uri = data.data!!
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
                                uri.toString().contains("com.google.android.apps.photos")) {
                                selectedUris.add(uri)
                                googlePhotosUris.add(uri)
                                // Store in current photo sources
                                val currentSources = getCurrentPhotoSources().toMutableSet()
                                currentSources.add(SOURCE_GOOGLE_PHOTOS)
                                pendingChanges["photo_sources"] = currentSources
                            } else {
                                try {
                                    requireContext().contentResolver.takePersistableUriPermission(
                                        uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                    selectedUris.add(uri)

                                    // If it's a Google Photos URI, add to list for caching
                                    if (uri.toString().contains("com.google.android.apps.photos") ||
                                        uri.toString().contains("googleusercontent.com")) {
                                        googlePhotosUris.add(uri)
                                    }
                                } catch (e: SecurityException) {
                                    Log.e(TAG, "Failed to take permission for URI: $uri", e)
                                    selectedUris.add(uri)

                                    // If it's a Google Photos URI, add to list for caching
                                    if (uri.toString().contains("com.google.android.apps.photos") ||
                                        uri.toString().contains("googleusercontent.com")) {
                                        googlePhotosUris.add(uri)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedUris.isNotEmpty()) {
                        // Show a loading indicator if we have Google Photos to cache
                        if (googlePhotosUris.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                showLoadingIndicator(true, getString(R.string.caching_google_photos))
                            }
                        }

                        // Start caching Google Photos in the background if needed
                        val cachedUriMap = mutableMapOf<String, String>()
                        if (googlePhotosUris.isNotEmpty()) {
                            try {
                                // Cache the photos and collect progress updates
                                persistentPhotoCache.cachePhotos(googlePhotosUris.map { it.toString() })
                                    .collect { progress ->
                                        when (progress) {
                                            is PersistentPhotoCache.CachingProgress.InProgress -> {
                                                val progressPercentage = (progress.progress * 100).toInt()
                                                withContext(Dispatchers.Main) {
                                                    updateLoadingProgress(
                                                        getString(
                                                            R.string.caching_photos_progress,
                                                            progress.completed,
                                                            progress.total,
                                                            progressPercentage
                                                        )
                                                    )
                                                }
                                            }
                                            is PersistentPhotoCache.CachingProgress.Complete -> {
                                                Log.d(TAG, "Photo caching completed: ${progress.succeeded} succeeded, ${progress.failed} failed")

                                                // Get the mapping of original URIs to cached URIs
                                                for (uri in googlePhotosUris) {
                                                    val originalUriStr = uri.toString()
                                                    val cachedUriStr = persistentPhotoCache.getCachedPhotoUri(originalUriStr)
                                                    if (cachedUriStr != null) {
                                                        cachedUriMap[originalUriStr] = cachedUriStr
                                                        Log.d(TAG, "Cached URI mapping: $originalUriStr -> $cachedUriStr")
                                                    }
                                                }
                                            }
                                            else -> { /* Handle other states as needed */ }
                                        }
                                    }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error caching Google Photos", e)
                            } finally {
                                withContext(Dispatchers.Main) {
                                    showLoadingIndicator(false)
                                }
                            }
                        }

                        // Create MediaItems and add to repository, using cached URIs where available
                        val mediaItems = selectedUris.map { uri ->
                            val originalUriStr = uri.toString()
                            val baseUrl = cachedUriMap[originalUriStr] ?: originalUriStr

                            MediaItem(
                                id = originalUriStr, // Keep original URI as ID for reference
                                albumId = "google_picked",
                                baseUrl = baseUrl, // Use cached URI if available
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

                        // Update sources and state
                        val currentSources = getCurrentPhotoSources().toMutableSet()
                        currentSources.add(SOURCE_GOOGLE_PHOTOS)
                        pendingChanges["photo_sources"] = currentSources
                        pendingChanges["google_photos_enabled"] = true

                        withContext(Dispatchers.Main) {
                            findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = true
                            updateGooglePhotosState(true)
                            applyChanges()

                            // Show a toast with caching results if applicable
                            if (googlePhotosUris.isNotEmpty()) {
                                val cachedCount = cachedUriMap.size
                                if (cachedCount > 0) {
                                    val message = getString(
                                        R.string.photos_cached_success,
                                        cachedCount,
                                        googlePhotosUris.size
                                    )
                                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                                }
                            }

                            notifyPhotosAdded()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing selected photos", e)
                    withContext(Dispatchers.Main) {
                        showLoadingIndicator(false)
                        showError(getString(R.string.photo_processing_error))
                    }
                }
            }
        }
    }

    private fun showLoadingIndicator(show: Boolean, message: String = "") {
        try {
            val progressBar = view?.findViewById<View>(R.id.loading_indicator)
            val textView = view?.findViewById<TextView>(R.id.loading_text)

            if (progressBar != null && textView != null) {
                if (show) {
                    progressBar.visibility = View.VISIBLE
                    textView.text = message
                    textView.visibility = View.VISIBLE
                } else {
                    progressBar.visibility = View.GONE
                    textView.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing/hiding loading indicator", e)
        }
    }

    private fun updateLoadingProgress(message: String) {
        try {
            val textView = view?.findViewById<TextView>(R.id.loading_text)
            textView?.text = message
        } catch (e: Exception) {
            Log.e(TAG, "Error updating loading progress", e)
        }
    }

    private fun handleGooglePhotoCaching(selectedUris: List<Uri>) {
        val googlePhotoUris = selectedUris
            .map { it.toString() }
            .filter { it.contains("photos.google.com") || it.contains("googleusercontent.com") }

        if (googlePhotoUris.isNotEmpty()) {
            lifecycleScope.launch {
                try {
                    // Show loading indicator
                    val progressBar = view?.findViewById<View>(R.id.loading_indicator)
                    val loadingText = view?.findViewById<TextView>(R.id.loading_text)

                    if (progressBar != null) progressBar.visibility = View.VISIBLE
                    if (loadingText != null) {
                        loadingText.text = getString(R.string.caching_photos)
                        loadingText.visibility = View.VISIBLE
                    }

                    // Start caching process
                    persistentPhotoCache.cachePhotos(googlePhotoUris)
                        .collect { progress ->
                            when (progress) {
                                is PersistentPhotoCache.CachingProgress.InProgress -> {
                                    if (loadingText != null) {
                                        loadingText.text = getString(
                                            R.string.caching_photos_progress,
                                            progress.completed,
                                            progress.total,
                                            (progress.progress * 100).toInt()
                                        )
                                    }
                                }
                                is PersistentPhotoCache.CachingProgress.Complete -> {
                                    if (progressBar != null) progressBar.visibility = View.GONE
                                    if (loadingText != null) loadingText.visibility = View.GONE

                                    // Notify user of completion
                                    val message = getString(
                                        R.string.photos_cached_success,
                                        progress.succeeded,
                                        googlePhotoUris.size
                                    )
                                    view?.let {
                                        Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show()
                                    }
                                }
                                is PersistentPhotoCache.CachingProgress.Failed -> {
                                    if (progressBar != null) progressBar.visibility = View.GONE
                                    if (loadingText != null) loadingText.visibility = View.GONE

                                    // Notify user of failure
                                    val message = getString(
                                        R.string.photos_cached_error,
                                        progress.completed,
                                        progress.errors
                                    )
                                    view?.let {
                                        Snackbar.make(it, message, Snackbar.LENGTH_LONG).show()
                                    }
                                }
                                else -> {}
                            }
                        }
                } catch (e: Exception) {
                    val progressBar = view?.findViewById<View>(R.id.loading_indicator)
                    val loadingText = view?.findViewById<TextView>(R.id.loading_text)

                    if (progressBar != null) progressBar.visibility = View.GONE
                    if (loadingText != null) loadingText.visibility = View.GONE

                    view?.let {
                        Snackbar.make(
                            it,
                            getString(R.string.photos_cached_error_general, e.message),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private suspend fun copyUriContent(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val fileName = "photo_${System.currentTimeMillis()}.jpg"
            val outputFile = File(requireContext().getExternalFilesDir(null), fileName)

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy content: $uri", e)
            null
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