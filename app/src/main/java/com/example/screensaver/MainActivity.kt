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
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : AppCompatActivity() {
    private lateinit var mGoogleSignInClient: GoogleSignInClient

    companion object {
        private const val TAG = "MainActivity"

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
        }
    }

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "Google Sign In successful")
            GlobalAccountManager.setGoogleAccount(account)
            if (supportFragmentManager.fragments.isEmpty()) {
                findViewById<BottomNavigationView>(R.id.bottom_navigation)?.selectedItemId =
                    R.id.navigation_screensaver
            }
        } catch (e: ApiException) {
            Log.w(TAG, "Google Sign In failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope("https://www.googleapis.com/auth/photoslibrary.readonly"),
                Scope("https://www.googleapis.com/auth/photos.readonly") // Add this scope
            )
            .requestIdToken(getString(R.string.default_web_client_id)) // Add this line
            .build()

        mGoogleSignInClient = GoogleSignIn.getClient(this, gso)

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            signIn()
        } else {
            GlobalAccountManager.setGoogleAccount(account)
        }

        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_screensaver -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, WebViewFragment())
                        .commit()
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

        if (savedInstanceState == null) {
            bottomNavigation.selectedItemId = R.id.navigation_screensaver
        }
    }

    private fun signIn() {
        val signInIntent = mGoogleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear the account when the activity is destroyed
        GlobalAccountManager.setGoogleAccount(null)
    }
}