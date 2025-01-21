package com.example.screensaver

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : AppCompatActivity() {
    private lateinit var mGoogleSignInClient: GoogleSignInClient

    // Register the Activity Result launcher
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            // Signed in successfully
            Log.d("MainActivity", "Google Sign In successful")
            // Store the account for use in fragments
            AccountManager.setGoogleAccount(account)
            // Proceed with normal app flow
            if (supportFragmentManager.fragments.isEmpty()) {
                findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId =
                    R.id.navigation_screensaver
            }
        } catch (e: ApiException) {
            Log.w("MainActivity", "Google Sign In failed", e)
            // Handle sign-in failure
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/photoslibrary.readonly"))
            .build()

        mGoogleSignInClient = GoogleSignIn.getClient(this, gso)

        // Check for existing Google Sign In account
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            signIn()
        }

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_screensaver -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, WebViewFragment.newInstance())
                        .commit()
                    true
                }
                R.id.navigation_settings -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, SettingsFragment.newInstance())
                        .commit()
                    true
                }
                else -> false
            }
        }

        // Set initial fragment
        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.navigation_screensaver
        }
    }

    private fun signIn() {
        val signInIntent = mGoogleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    companion object {
        private const val TAG = "MainActivity"

        object AccountManager {
            private var googleAccount: GoogleSignInAccount? = null

            fun setGoogleAccount(account: GoogleSignInAccount?) {
                googleAccount = account
                Log.d(TAG, "Setting Google Account: ${account?.email}")
            }

            fun getGoogleAccount(): GoogleSignInAccount? {
                Log.d(TAG, "Getting Google Account: ${googleAccount?.email}")
                return googleAccount
            }
        }
    }
}