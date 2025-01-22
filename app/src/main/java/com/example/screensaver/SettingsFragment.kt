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
                .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .requestIdToken(getString(R.string.google_oauth_client_id))
                .build()

            googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

            findPreference<SwitchPreference>("use_google_photos")?.apply {
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        showGoogleSignInPrompt()
                        false
                    } else {
                        signOut()
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
            Log.d(TAG, "Granted scopes: ${account.grantedScopes?.joinToString()}")

            val hasRequiredScope = account.grantedScopes?.contains(
                Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
            ) == true

            if (hasRequiredScope) {
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putString("google_account", account.email)
                    .putString("google_access_token", account.idToken)
                    .putString("google_server_auth_code", account.serverAuthCode) // Add this line
                    .apply()

                // Save account to global state
                GoogleAccountHolder.currentAccount = account

                updateGooglePhotosState(true)
                Toast.makeText(context, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
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