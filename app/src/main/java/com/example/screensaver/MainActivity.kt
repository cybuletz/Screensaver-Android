package com.example.screensaver

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : AppCompatActivity() {
    private lateinit var mGoogleSignInClient: GoogleSignInClient
    private lateinit var bottomNavigation: BottomNavigationView
    private var isSigningIn = false

    companion object {
        private const val TAG = "MainActivity"
        private const val SIGN_IN_RETRY_MAX = 3
        private var signInRetryCount = 0

        object GlobalAccountManager {
            private const val TAG = "AccountManager"
            private var googleAccount: GoogleSignInAccount? = null

            fun setGoogleAccount(account: GoogleSignInAccount?) {
                googleAccount = account
                Log.d(TAG, "Setting Google Account: ${account?.email}")
            }

            fun getGoogleAccount(): GoogleSignInAccount? {
                Log.d(TAG, "Getting Google Account: ${googleAccount?.email}")
                return googleAccount
            }

            fun hasValidToken(): Boolean {
                return googleAccount?.idToken != null
            }

            fun clearAccount() {
                googleAccount = null
                Log.d(TAG, "Cleared Google Account")
            }
        }
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (isSigningIn) {
                Log.d(TAG, "Sign-in already in progress, ignoring result")
                return@registerForActivityResult
            }
            isSigningIn = true

            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)

            if (account.idToken == null) {
                Log.e(TAG, "Google Sign In successful but no ID token received")
                handleSignInError("No ID token received")
                return@registerForActivityResult
            }

            // Verify we have all required scopes
            val requiredScopes = getRequiredScopes()
            val grantedScopes = account.grantedScopes ?: emptySet()

            if (!grantedScopes.containsAll(requiredScopes)) {
                Log.e(TAG, "Missing required scopes")
                handleSignInError("Missing required permissions")
                signOut(silent = true)
                return@registerForActivityResult
            }

            Log.d(TAG, "Google Sign In successful with all required scopes")
            GlobalAccountManager.setGoogleAccount(account)
            signInRetryCount = 0
            loadWebViewFragment()

        } catch (e: ApiException) {
            when (e.statusCode) {
                12500 -> handleOAuthError()
                else -> handleSignInError("Sign in failed: ${e.message}")
            }
        } finally {
            isSigningIn = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            setupGoogleSignIn()
            setupBottomNavigation()

            // Check for existing sign in
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (account?.idToken == null) {
                signIn()
            } else {
                // Verify token validity and scopes
                if (account.grantedScopes?.containsAll(getRequiredScopes()) == true) {
                    GlobalAccountManager.setGoogleAccount(account)
                    if (savedInstanceState == null) {
                        bottomNavigation.selectedItemId = R.id.navigation_screensaver
                    }
                } else {
                    Log.d(TAG, "Existing account missing required scopes, signing in again")
                    signOut(silent = true)
                    signIn()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during onCreate", e)
            showError("Failed to initialize app: ${e.message}")
        }
    }

    private fun setupGoogleSignIn() {
        try {
            val clientId = getString(R.string.default_web_client_id)
            Log.d(TAG, "Setting up Google Sign In with client ID: ${clientId.take(5)}...")

            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .requestId()
                .requestIdToken(clientId) // Make sure this matches your google-services.json
                .requestScopes(
                    Scope("https://www.googleapis.com/auth/photoslibrary.readonly"),
                    Scope("https://www.googleapis.com/auth/photoslibrary"),
                    Scope("https://www.googleapis.com/auth/photos.readonly")
                )
                .build()

            mGoogleSignInClient = GoogleSignIn.getClient(this, gso)
            Log.d(TAG, "Google Sign In client initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Google Sign In", e)
            showError("Failed to initialize Google Sign In")
        }
    }

    private fun getRequiredScopes(): Set<Scope> {
        return setOf(
            Scope("https://www.googleapis.com/auth/photoslibrary.readonly"),
            Scope("https://www.googleapis.com/auth/photoslibrary"),
            Scope("https://www.googleapis.com/auth/photos.readonly")
        )
    }

    private fun setupBottomNavigation() {
        bottomNavigation = findViewById(R.id.bottom_navigation)

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_screensaver -> {
                    if (GlobalAccountManager.hasValidToken()) {
                        loadWebViewFragment()
                    } else {
                        signIn()
                    }
                    true
                }
                R.id.navigation_settings -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, SettingsFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    private fun handleOAuthError() {
        Log.e(TAG, "OAuth configuration error (12500)")
        signInRetryCount++

        if (signInRetryCount < SIGN_IN_RETRY_MAX) {
            Log.d(TAG, "Retrying sign-in (attempt $signInRetryCount)")
            showError("Authentication error, retrying...")
            setupGoogleSignIn() // Reinitialize the client
            signIn()
        } else {
            showError("Failed to authenticate. Please check app configuration and try again later.")
            Log.e(TAG, "Max sign-in retries reached")
        }
    }

    private fun handleSignInError(message: String) {
        showError(message)
        signInRetryCount++

        if (signInRetryCount < SIGN_IN_RETRY_MAX) {
            Log.d(TAG, "Retrying sign-in after error (attempt $signInRetryCount)")
            signIn()
        } else {
            Log.e(TAG, "Max sign-in retries reached")
            showError("Unable to sign in. Please try again later.")
        }
    }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun loadWebViewFragment() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, WebViewFragment())
            .commit()
    }

    fun signIn() {
        if (isSigningIn) {
            Log.d(TAG, "Sign-in already in progress")
            return
        }
        val signInIntent = mGoogleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    fun signOut(silent: Boolean = false) {
        mGoogleSignInClient.signOut().addOnCompleteListener {
            GlobalAccountManager.clearAccount()
            if (!silent) {
                signIn()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            GlobalAccountManager.clearAccount()
        }
    }
}