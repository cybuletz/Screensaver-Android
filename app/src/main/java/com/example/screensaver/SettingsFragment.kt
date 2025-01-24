import com.example.screensaver.R

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import androidx.preference.Preference
import android.content.pm.PackageInfo
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.security.MessageDigest
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.MultiSelectListPreference
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.lock.PhotoLockActivity
import com.example.screensaver.lock.PhotoLockScreenService
import com.example.screensaver.lock.PhotoLockDeviceAdmin
import com.example.screensaver.AlbumSelectionActivity
import kotlinx.coroutines.coroutineScope
import com.example.screensaver.shared.GooglePhotosManager
import android.app.Activity
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupPhotoSourcePreferences()
        setupGoogleSignIn()
        setupTestScreensaver()
        setupDisplayModeSelection()
    }

    private var googleSignInClient: GoogleSignInClient? = null

    private val scope get() = lifecycleScope


    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Sign in result received: ${result.resultCode}")
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {  // Use fully qualified name
                handleSignInResult(GoogleSignIn.getSignedInAccountFromIntent(result.data))
            }
            android.app.Activity.RESULT_CANCELED -> {  // Use fully qualified name
                Log.w(TAG, "Sign in cancelled by user")
                Toast.makeText(context, "Sign in cancelled", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
            }
            else -> {
                Log.e(TAG, "Sign in failed with result code: ${result.resultCode}")
                Toast.makeText(context, "Sign in failed", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
            }
        }
    }

    companion object {
        private const val TAG = "SettingsFragment"
    }

//    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
//        setPreferencesFromResource(R.xml.preferences, rootKey)
//        setupPhotoSourcePreferences()
//        setupGoogleSignIn()
//        setupTestScreensaver()
//    }

    private fun setupDisplayModeSelection() {
        findPreference<ListPreference>("display_mode_selection")?.setOnPreferenceChangeListener { _, newValue ->
            when (newValue as String) {
                "dream_service" -> {
                    // Handle dream service selection
                    startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
                }
                "lock_screen" -> {
                    // Handle lock screen selection
                    val intent = Intent(requireContext(), PhotoLockActivity::class.java)
                    intent.putExtra("preview_mode", true)
                    startActivity(intent)
                }
            }
            true
        }
    }

    private fun setupTestScreensaver() {
        findPreference<Preference>("test_screensaver")?.setOnPreferenceClickListener {
            val displayMode = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getString("display_mode_selection", "dream_service")

            when (displayMode) {
                "dream_service" -> startScreensaver()
                "lock_screen" -> {
                    val intent = Intent(requireContext(), PhotoLockActivity::class.java)
                    intent.putExtra("preview_mode", true)
                    startActivity(intent)
                }
            }
            true
        }
    }

    private fun startScreensaver() {
        try {
            // Get the Dream (Screensaver) component name
            val dreamComponent = requireContext().packageManager
                .resolveService(
                    Intent("android.service.dreams.DreamService")
                        .setPackage(requireContext().packageName),
                    PackageManager.MATCH_DEFAULT_ONLY
                )?.serviceInfo?.name

            if (dreamComponent != null) {
                // Create an intent to start the screensaver
                val intent = Intent(Settings.ACTION_DREAM_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    requireContext(),
                    "Please select and test the screensaver from system settings",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Screensaver service not found",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screensaver: ${e.message}")
            Toast.makeText(
                requireContext(),
                "Failed to start screensaver: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun setupGoogleSignIn() {
        try {
            Log.d(TAG, "Setting up Google Sign In")
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .build()

            Log.d(TAG, "GoogleSignInOptions built successfully")
            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
            Log.d(TAG, "GoogleSignInClient created")

            findPreference<SwitchPreference>("use_google_photos")?.apply {
                Log.d(TAG, "Found use_google_photos preference")

                setOnPreferenceChangeListener { _, newValue ->
                    Log.d(TAG, "use_google_photos preference changed to: $newValue")
                    if (newValue as Boolean) {
                        showGoogleSignInPrompt()
                        false
                    } else {
                        signOutCompletely()
                        true
                    }
                }

                // Check if already signed in
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                Log.d(TAG, "Current Google account: ${account?.email}")

                val hasRequiredScope = account?.grantedScopes?.contains(
                    Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
                ) == true
                Log.d(TAG, "Has required scope: $hasRequiredScope")

                isChecked = account != null && hasRequiredScope
                Log.d(TAG, "Set use_google_photos switch to: $isChecked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupGoogleSignIn", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPhotoSourcePreferences() {
        val photoSourceSelection = findPreference<MultiSelectListPreference>("photo_source_selection")
        val googlePhotosCategory = findPreference<PreferenceCategory>("google_photos_settings")
        val useGooglePhotos = findPreference<SwitchPreferenceCompat>("use_google_photos")
        val selectAlbums = findPreference<Preference>("select_albums")

        Log.d(TAG, "Setting up photo source preferences")

        // Debug current state
        val currentSources = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getStringSet("photo_source_selection", setOf("local")) ?: setOf("local")
        Log.d(TAG, "Current photo sources: $currentSources")

        // Update UI based on current state
        googlePhotosCategory?.isVisible = currentSources.contains("google_photos")
        Log.d(TAG, "Google Photos category visibility: ${googlePhotosCategory?.isVisible}")

        useGooglePhotos?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            Log.d(TAG, "Google Photos switch toggled: $enabled")
            if (enabled) {
                try {
                    Log.d(TAG, "Attempting to show Google Sign In prompt")
                    showGoogleSignInPrompt()
                    false // Don't update switch yet
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing Google Sign In prompt", e)
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    false
                }
            } else {
                signOutCompletely()
                true
            }
        }

        selectAlbums?.setOnPreferenceClickListener {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                Log.d(TAG, "Select albums clicked, account: ${account?.email}")

                if (account != null) {
                    val intent = Intent(requireContext(), AlbumSelectionActivity::class.java)
                    Log.d(TAG, "Starting AlbumSelectionActivity")
                    startActivity(intent)
                    Log.d(TAG, "AlbumSelectionActivity started successfully")
                    true
                } else {
                    Log.w(TAG, "No Google account found")
                    Toast.makeText(context, "Please sign in with Google first", Toast.LENGTH_SHORT).show()
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error launching album selection", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                false
            }
        }

        photoSourceSelection?.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("UNCHECKED_CAST")
            val selectedSources = newValue as Set<String>
            Log.d(TAG, "Photo sources changed to: $selectedSources")

            googlePhotosCategory?.isVisible = selectedSources.contains("google_photos")
            if (!selectedSources.contains("google_photos") && currentSources.contains("google_photos")) {
                signOutCompletely()
            }
            true
        }
    }


    private fun showGoogleSignInPrompt() {
        try {
            Log.d(TAG, "Configuring Google Sign In")
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .build()

            Log.d(TAG, "Client ID being used: ${getString(R.string.google_oauth_client_id).take(10)}...")

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            Log.d(TAG, "Created GoogleSignInClient successfully")

            // Check if we already have a signed in account
            val currentAccount = GoogleSignIn.getLastSignedInAccount(requireContext())
            Log.d(TAG, "Current account before sign out: ${currentAccount?.email}")

            Log.d(TAG, "Clearing existing sign in state")
            googleSignInClient?.signOut()?.addOnCompleteListener { signOutTask ->
                if (signOutTask.isSuccessful) {
                    Log.d(TAG, "Previous sign in state cleared, launching sign in")
                    val signInIntent = googleSignInClient?.signInIntent
                    Log.d(TAG, "Sign in intent created: ${signInIntent != null}")
                    signInLauncher.launch(signInIntent)
                } else {
                    Log.e(TAG, "Error clearing previous sign in", signOutTask.exception)
                    Toast.makeText(context, "Error preparing sign in", Toast.LENGTH_SHORT).show()
                    updateGooglePhotosState(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showGoogleSignInPrompt", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            updateGooglePhotosState(false)
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            account?.serverAuthCode?.let { authCode ->
                account.email?.let { email ->
                    exchangeAuthCode(authCode, email)
                }
            } ?: run {
                Log.e(TAG, "No server auth code received")
                Toast.makeText(context, "Failed to get auth code", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed", e)
            Toast.makeText(context, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            updateGooglePhotosState(false)
        }
    }

    private fun exchangeAuthCode(authCode: String, accountEmail: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tokenEndpoint = "https://oauth2.googleapis.com/token"
                val clientId = getString(R.string.google_oauth_client_id)
                val clientSecret = getString(R.string.google_oauth_client_secret)

                val connection = URL(tokenEndpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = StringBuilder()
                    .append("grant_type=authorization_code")
                    .append("&code=").append(authCode)
                    .append("&client_id=").append(clientId)
                    .append("&client_secret=").append(clientSecret)
                    .append("&redirect_uri=").append("http://localhost") // Or your configured redirect URI
                    .toString()

                connection.outputStream.use { it.write(postData.toByteArray()) }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)

                    // Save all tokens
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit()
                        .putString("access_token", jsonResponse.getString("access_token"))
                        .putString("refresh_token", jsonResponse.getString("refresh_token"))
                        .putLong("token_expiration", System.currentTimeMillis() + (jsonResponse.getLong("expires_in") * 1000))
                        .putString("google_account", accountEmail)
                        .apply()

                    withContext(Dispatchers.Main) {
                        updateGooglePhotosState(true)
                        Toast.makeText(context, "Signed in as $accountEmail", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    Log.e(TAG, "Failed to exchange auth code: $error")
                    withContext(Dispatchers.Main) {
                        updateGooglePhotosState(false)
                        Toast.makeText(context, "Failed to complete sign in", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code", e)
                withContext(Dispatchers.Main) {
                    updateGooglePhotosState(false)
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun signOutCompletely() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("google_account")
                .remove("google_access_token")
                .apply()

            // Clear any stored credentials
            val account = GoogleSignIn.getLastSignedInAccount(requireContext())
            if (account != null) {
                googleSignInClient?.revokeAccess()?.addOnCompleteListener {
                    updateGooglePhotosState(false)
                    Toast.makeText(context, "Signed out completely", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Account access revoked")
                }
            }
        }
    }

    private fun signOut() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            Log.d(TAG, "Sign out completed")
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("google_account")
                .remove("google_access_token")
                .remove("google_server_auth_code")
                .apply()

            Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupGooglePhotos() {
        findPreference<SwitchPreferenceCompat>("use_google_photos")?.apply {
            lifecycleScope.launch {
                isChecked = googlePhotosManager.initialize()
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        showGoogleSignInPrompt()
                    } else {
                        googlePhotosManager.cleanup()
                    }
                    true
                }
            }
        }
    }

    private fun updateGooglePhotosState(enabled: Boolean) {
        Log.d(TAG, "Updating Google Photos state: enabled=$enabled")
        findPreference<SwitchPreferenceCompat>("use_google_photos")?.apply {
            isChecked = enabled
            Log.d(TAG, "Updated use_google_photos switch to: $isChecked")
        }
        findPreference<Preference>("select_albums")?.apply {
            isVisible = enabled
            Log.d(TAG, "Updated select_albums visibility to: $isVisible")
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(":") { "%02X".format(it) }
    }

}