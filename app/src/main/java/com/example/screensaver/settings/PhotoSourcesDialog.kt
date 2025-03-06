package com.example.screensaver.settings

import android.Manifest
import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.AlbumSelectionActivity
import com.example.screensaver.R
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.localphotos.LocalPhotoSelectionActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PhotoSourcesDialog : DialogFragment() {
    companion object {
        fun newInstance() = PhotoSourcesDialog()
        private const val TAG = "PhotoSourcesDialog"
    }

    private var photoSourcesFragment: PhotoSourcesPreferenceFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_photo_sources, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.dialog_toolbar).apply {
            title = getString(R.string.pref_photo_sources_title)
        }

        photoSourcesFragment = PhotoSourcesPreferenceFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.photo_sources_container, photoSourcesFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            photoSourcesFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            photoSourcesFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        photoSourcesFragment = null
        super.onDestroyView()
    }
}

@AndroidEntryPoint
class PhotoSourcesPreferenceFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var appDataManager: AppDataManager

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var secureStorage: SecureStorage

    private var googleSignInClient: GoogleSignInClient? = null
    private var pendingChanges = mutableMapOf<String, Any>()

    companion object {
        private const val REQUEST_SELECT_PHOTOS = 1001
        private const val EXTRA_PHOTO_SOURCE = "photo_source"
        private const val SOURCE_LOCAL_PHOTOS = "local_photos"
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.photo_sources_preferences, rootKey)
        setupPreferences()
    }

    private fun setupPreferences() {
        // Local Photos
        findPreference<SwitchPreferenceCompat>("local_photos_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                pendingChanges["photo_sources"] = if (enabled) {
                    getCurrentPhotoSources() + "local"
                } else {
                    getCurrentPhotoSources() - "local"
                }
                true
            }
        }

        findPreference<Preference>("select_local_photos")?.apply {
            setOnPreferenceClickListener {
                checkAndRequestStoragePermission()
                true
            }
        }

        // Google Photos
        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    initiateGoogleSignIn()
                    false // Don't update until sign-in completes
                } else {
                    signOutGoogle()
                    pendingChanges["google_photos_enabled"] = false
                    pendingChanges["photo_sources"] = getCurrentPhotoSources() - "google_photos"
                    true
                }
            }
        }

        findPreference<Preference>("select_google_albums")?.apply {
            setOnPreferenceClickListener {
                launchGoogleAlbumSelection()
                true
            }
        }

        restoreState()
    }

    private fun getCurrentPhotoSources(): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf()) ?: setOf()
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
        startActivityForResult(
            Intent(requireContext(), LocalPhotoSelectionActivity::class.java),
            REQUEST_SELECT_PHOTOS
        )
    }

    private fun initiateGoogleSignIn() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .build()

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            googleSignInClient?.signOut()?.addOnCompleteListener {
                signInLauncher.launch(googleSignInClient?.signInIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating Google Sign-in", e)
            showError(getString(R.string.sign_in_failed))
        }
    }

    private fun handleSignInResult(task: com.google.android.gms.tasks.Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            updateGooglePhotosState(true)
            pendingChanges["google_photos_enabled"] = true
            pendingChanges["photo_sources"] = getCurrentPhotoSources() + "google_photos"

            findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked = true
            findPreference<Preference>("select_google_albums")?.isEnabled = true

        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed", e)
            updateGooglePhotosState(false)
            showError(getString(R.string.sign_in_failed))
        }
    }

    private fun signOutGoogle() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            updateGooglePhotosState(false)
        }
    }

    private fun launchGoogleAlbumSelection() {
        lifecycleScope.launch {
            try {
                if (googlePhotosManager.initialize()) {
                    startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java))
                } else {
                    showError(getString(R.string.google_photos_init_failed))
                }
            } catch (e: Exception) {
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
            currentSources.contains("local")

        findPreference<SwitchPreferenceCompat>("google_photos_enabled")?.isChecked =
            currentSources.contains("google_photos")

        updateGooglePhotosState(GoogleSignIn.getLastSignedInAccount(requireContext()) != null)
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
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SELECT_PHOTOS && resultCode == Activity.RESULT_OK) {
            data?.getStringArrayListExtra("selected_photos")?.let { selectedPhotos ->
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putStringSet("selected_local_photos", selectedPhotos.toSet())
                    .apply()
            }
        }
    }
}