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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.security.MessageDigest
import androidx.preference.Preference

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
        setPreferencesFromResource(R.xml.preferences, rootKey)
        setupGoogleSignIn()
    }

    private fun setupGoogleSignIn() {
        // Log SHA-1 and package info
        try {
            val packageInfo = requireContext().packageManager.getPackageInfo(
                requireContext().packageName,
                PackageManager.GET_SIGNATURES
            )
            packageInfo.signatures.forEach { signature ->
                val md = MessageDigest.getInstance("SHA1")
                md.update(signature.toByteArray())
                val sha1 = bytesToHex(md.digest())
                Log.d(TAG, "Package: ${requireContext().packageName}")
                Log.d(TAG, "SHA-1: $sha1")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SHA-1", e)
        }

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
            ) ?: false

            isChecked = account != null && hasRequiredScope
            Log.d(TAG, "Current sign in status: ${if (account != null) "Signed in" else "Not signed in"}")
        }
    }

    private fun showGoogleSignInPrompt() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
                .build()  // Remove .requestIdToken() for now

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
            Log.d(TAG, "Granted scopes: ${account.grantedScopes?.joinToString()}")

            val hasRequiredScope = account.grantedScopes?.contains(
                Scope("https://www.googleapis.com/auth/photoslibrary.readonly")
            ) ?: false

            if (hasRequiredScope) {
                // Save credentials
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit()
                    .putString("google_account", account.email)
                    .putString("google_access_token", account.idToken)
                    .apply()

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
            Log.e(TAG, "Status message: ${e.statusMessage}")
            Toast.makeText(context, "Sign in failed: ${e.statusCode}", Toast.LENGTH_SHORT).show()
            updateGooglePhotosState(false)
        }
    }

    private fun signOut() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            Log.d(TAG, "Sign out completed")
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .remove("google_account")
                .remove("google_access_token")
                .apply()
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
        val hexChars = CharArray(bytes.size * 3)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 3] = "0123456789ABCDEF"[v ushr 4]
            hexChars[i * 3 + 1] = "0123456789ABCDEF"[v and 0x0F]
            hexChars[i * 3 + 2] = ':'
        }
        return String(hexChars, 0, hexChars.size - 1)
    }

    companion object {
        private const val TAG = "SettingsFragment"
    }
}