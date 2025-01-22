package com.example.screensaver

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
import java.security.MessageDigest
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SettingsFragment : PreferenceFragmentCompat() {
    private var googleSignInClient: GoogleSignInClient? = null

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Sign in result received: ${result.resultCode}")
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                handleSignInResult(GoogleSignIn.getSignedInAccountFromIntent(result.data))
            }
            android.app.Activity.RESULT_CANCELED -> {
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        // Set up Google Sign-in
        setupGoogleSignIn()

        // Set up Google Photos album selection
        setupGooglePhotos()

        // Add a preference for manual trigger
        findPreference<Preference>("test_screensaver")?.setOnPreferenceClickListener {
            startScreensaver()
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
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestIdToken(getString(R.string.google_oauth_client_id))  // We need the ID token
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true) // Force refresh token
                .build()

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            findPreference<SwitchPreference>("use_google_photos")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        showGoogleSignInPrompt()
                        false
                    } else {
                        signOutCompletely() // Use complete sign out
                        true
                    }
                }

                // Check if already signed in
                val account = GoogleSignIn.getLastSignedInAccount(requireContext())
                val hasRequiredScope = account?.grantedScopes?.contains(
                    Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
                ) == true

                isChecked = account != null && hasRequiredScope
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupGoogleSignIn", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showGoogleSignInPrompt() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true) // Add this line
                .requestIdToken(getString(R.string.google_oauth_client_id))             // Add this line
                .build()

            Log.d(TAG, "Configuring Google Sign In")
            Log.d(TAG, "Requested scopes: ${gso.scopeArray.joinToString()}")

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso).also { client ->
                client.signOut().addOnCompleteListener {
                    Log.d(TAG, "Previous sign in state cleared")
                    signInLauncher.launch(client.signInIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Google Sign In", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            updateGooglePhotosState(false)
        }
    }

    private fun handleSignInResult(completedTask: com.google.android.gms.tasks.Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            Log.d(TAG, "Sign in successful for: ${account.email}")
            Log.d(TAG, "ID Token present: ${account.idToken != null}")
            Log.d(TAG, "Server Auth Code present: ${account.serverAuthCode != null}")

            val hasRequiredScope = account.grantedScopes?.contains(
                Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
            ) == true

            if (hasRequiredScope) {
                // Pass both auth code and account email
                exchangeAuthCode(account.serverAuthCode ?: "", account.email ?: "")
            } else {
                Log.e(TAG, "Required scope not granted")
                Toast.makeText(context, "Required permissions not granted", Toast.LENGTH_SHORT).show()
                updateGooglePhotosState(false)
                signOut()
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign in failed code=${e.statusCode}")
            Log.e(TAG, "Sign in error: ${e.message}")
            Toast.makeText(context, "Sign in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
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
                    .append("&redirect_uri=").append("com.example.screensaver:/oauth2redirect")
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
                .remove("google_server_auth_code")  // Add this line
                .apply()

            // Clear the current account
            GoogleAccountHolder.currentAccount = null

            Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupGooglePhotos() {
        findPreference<Preference>("select_albums")?.setOnPreferenceClickListener {
            if (GoogleSignIn.getLastSignedInAccount(requireContext()) != null) {
                startActivity(Intent(requireContext(), AlbumSelectionActivity::class.java))
                true
            } else {
                Toast.makeText(context, "Please sign in with Google first", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    private fun updateGooglePhotosState(enabled: Boolean) {
        findPreference<SwitchPreference>("use_google_photos")?.isChecked = enabled
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    companion object {
        private const val TAG = "SettingsFragment"
    }
}