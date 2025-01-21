package com.example.screensaver

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val RC_SIGN_IN = 9001
        private const val DEBUG = true

        // Make GlobalAccountManager accessible to WebViewFragment
        object GlobalAccountManager {
            private var googleAccount: GoogleSignInAccount? = null

            fun setGoogleAccount(account: GoogleSignInAccount?) {
                googleAccount = account
            }

            fun getGoogleAccount(): GoogleSignInAccount? {
                return googleAccount
            }
        }
    }

    private lateinit var mGoogleSignInClient: GoogleSignInClient
    private var signInAttempts = 0
    private val maxSignInAttempts = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupGoogleSignIn()
    }

    private fun setupGoogleSignIn() {
        try {
            val clientId = getString(R.string.default_web_client_id)
            if (DEBUG) {
                Log.d(TAG, "Full Client ID: $clientId")
                printSigningInfo()
            }

            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestId()
                .requestIdToken(clientId)
                .requestScopes(
                    Scope("https://www.googleapis.com/auth/photoslibrary.readonly"),
                    Scope("https://www.googleapis.com/auth/photoslibrary"),
                    Scope("https://www.googleapis.com/auth/photos.readonly")
                )
                .build()

            mGoogleSignInClient = GoogleSignIn.getClient(this, gso)
            Log.d(TAG, "Google Sign In client initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error in setupGoogleSignIn", e)
            showDetailedError("Setup Error", e)
        }
    }

    // Make signIn public so WebViewFragment can access it
    fun signIn() {
        if (signInAttempts >= maxSignInAttempts) {
            Log.e(TAG, "Max sign-in attempts reached")
            showDetailedError("Max Attempts", Exception("Maximum sign-in attempts reached"))
            return
        }

        try {
            if (GoogleSignIn.getLastSignedInAccount(this) != null) {
                Log.d(TAG, "Already signed in, clearing account...")
                mGoogleSignInClient.signOut().addOnCompleteListener {
                    startSignIn()
                }
            } else {
                startSignIn()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in signIn", e)
            showDetailedError("Sign In Error", e)
        }
    }

    private fun startSignIn() {
        try {
            signInAttempts++
            Log.d(TAG, "Starting sign-in attempt $signInAttempts")
            val signInIntent = mGoogleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        } catch (e: Exception) {
            Log.e(TAG, "Error in startSignIn", e)
            showDetailedError("Start Sign In Error", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                handleSignInResult(task)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onActivityResult", e)
                showDetailedError("Activity Result Error", e)
            }
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            Log.d(TAG, "Sign-in successful: ${account.email}")
            GlobalAccountManager.setGoogleAccount(account)
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed with code: ${e.statusCode}")
            val errorDetails = GoogleSignInStatusCodes.getStatusCodeString(e.statusCode)
            showDetailedError("Sign In Failed", Exception("Code: ${e.statusCode}, Details: $errorDetails"))

            if (e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS) {
                Log.d(TAG, "Sign-in already in progress")
                return
            }

            if (signInAttempts < maxSignInAttempts) {
                Log.d(TAG, "Retrying sign-in (attempt $signInAttempts)")
                signIn()
            }
        }
    }

    private fun showDetailedError(title: String, error: Exception) {
        val message = """
            Error: $title
            Message: ${error.message}
            Class: ${error.javaClass.simpleName}
            Stack: ${error.stackTrace.take(3).joinToString("\n")}
        """.trimIndent()

        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        AlertDialog.Builder(this)
            .setTitle("Error Details")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun printSigningInfo() {
        try {
            val info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES
            )

            for (signature in info.signatures) {
                val md = MessageDigest.getInstance("SHA1")
                md.update(signature.toByteArray())

                // Get Base64 format
                val sha1Base64 = Base64.encodeToString(md.digest(), Base64.DEFAULT)
                Log.d(TAG, "SHA1 (Base64): $sha1Base64")

                // Get hex format with colons (for Google Cloud Console)
                val sha1Hex = md.digest().joinToString(":") { String.format("%02X", it) }
                Log.d(TAG, "SHA1 (Hex): $sha1Hex")

                // Print message to help user
                Log.d(TAG, "Use the SHA1 (Hex) format in Google Cloud Console")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting signing info", e)
        }
    }
}