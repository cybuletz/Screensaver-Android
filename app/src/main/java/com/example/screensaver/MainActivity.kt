package com.example.screensaver

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

class MainActivity : AppCompatActivity() {
    private lateinit var mGoogleSignInClient: GoogleSignInClient
    private val RC_SIGN_IN = 9001

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
                        .replace(R.id.fragment_container, SettingsFragment())
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
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                // Signed in successfully
                Log.d("MainActivity", "Google Sign In successful")
                // Store the account for use in fragments
                AccountManager.setGoogleAccount(account)
                // Proceed with normal app flow
                if (supportFragmentManager.fragments.isEmpty()) {
                    bottomNavigation?.selectedItemId = R.id.navigation_screensaver
                }
            } catch (e: ApiException) {
                Log.w("MainActivity", "Google Sign In failed", e)
                // Handle sign-in failure
            }
        }
    }

    companion object {
        // Singleton to hold the Google Account
        object AccountManager {
            private var googleAccount: com.google.android.gms.auth.api.signin.GoogleSignInAccount? = null

            fun setGoogleAccount(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount?) {
                googleAccount = account
            }

            fun getGoogleAccount() = googleAccount
        }
    }
}