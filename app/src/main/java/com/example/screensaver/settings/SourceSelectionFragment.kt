package com.example.screensaver.settings

import android.app.Activity
import android.content.ContentValues.TAG
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.screensaver.R
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.databinding.FragmentSourceSelectionBinding
import com.example.screensaver.shared.GooglePhotosManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject

@AndroidEntryPoint
class SourceSelectionFragment : Fragment(), WizardStep {
    private var _binding: FragmentSourceSelectionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SourceSelectionState by activityViewModels()

    private var isAuthenticationInProgress = false

    @Inject
    lateinit var googlePhotosManager: GooglePhotosManager

    @Inject
    lateinit var secureStorage: SecureStorage

    private var googleSignInClient: GoogleSignInClient? = null

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                if (!isAuthenticationInProgress) {
                    isAuthenticationInProgress = true
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                                .getResult(ApiException::class.java)

                            account?.serverAuthCode?.let { authCode ->
                                account.email?.let { email ->
                                    val success = exchangeAuthCode(authCode, email)
                                    withContext(Dispatchers.Main) {
                                        isAuthenticationInProgress = false
                                        if (success) {
                                            binding.googleSourceSwitch.isChecked = true
                                            updateSelectedSources()
                                        } else {
                                            binding.googleSourceSwitch.isChecked = false
                                            showError(getString(R.string.sign_in_failed))
                                        }
                                    }
                                }
                            }
                        } catch (e: ApiException) {
                            withContext(Dispatchers.Main) {
                                isAuthenticationInProgress = false
                                binding.googleSourceSwitch.isChecked = false
                                updateSelectedSources()
                                showError(getString(R.string.sign_in_failed))
                            }
                        }
                    }
                }
            }
            else -> {
                isAuthenticationInProgress = false
                binding.googleSourceSwitch.isChecked = false
                updateSelectedSources()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSourceSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGoogleSignIn()
        setupSourceToggles()
        observeSelections()
        setupHelp()
        // Check if already authenticated
        if (googlePhotosManager.hasValidTokens()) {
            binding.googleSourceSwitch.isChecked = true
            updateSelectedSources()
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/photospicker.mediaitems.readonly"))
            .requestServerAuthCode(getString(R.string.google_oauth_client_id), true)
            .requestIdToken(getString(R.string.google_oauth_client_id))
            .build()

        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
    }

    private fun setupSourceToggles() {
        binding.apply {
            localSourceCard.setOnClickListener {
                localSourceSwitch.toggle()
            }
            googleSourceCard.setOnClickListener {
                googleSourceSwitch.toggle()
            }

            localSourceSwitch.setOnCheckedChangeListener { _, _ ->
                updateSelectedSources()
            }

            googleSourceSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !googlePhotosManager.hasValidTokens() && !isAuthenticationInProgress) {
                    // Prevent automatic check and initiate sign-in
                    googleSourceSwitch.isChecked = false
                    initiateGoogleSignIn()
                } else {
                    updateSelectedSources()
                }
            }
        }
    }

    private fun initiateGoogleSignIn() {
        if (!isAuthenticationInProgress) {
            isAuthenticationInProgress = true
            googleSignInClient?.signOut()?.addOnCompleteListener {
                signInLauncher.launch(googleSignInClient?.signInIntent)
            }
        }
    }

    private suspend fun exchangeAuthCode(authCode: String, email: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val tokenEndpoint = URL("https://oauth2.googleapis.com/token")
                val connection = (tokenEndpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                try {
                    val postData = buildString {
                        append("code=").append(URLEncoder.encode(authCode, "UTF-8"))
                        append("&client_id=").append(URLEncoder.encode(getString(R.string.google_oauth_client_id), "UTF-8"))
                        append("&client_secret=").append(URLEncoder.encode(getString(R.string.google_oauth_client_secret), "UTF-8"))
                        append("&redirect_uri=").append(URLEncoder.encode("http://localhost", "UTF-8"))
                        append("&grant_type=authorization_code")
                    }

                    connection.outputStream.use { it.write(postData.toByteArray()) }
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)

                    val accessToken = jsonResponse.getString("access_token")
                    val refreshToken = jsonResponse.getString("refresh_token")
                    val expiresIn = jsonResponse.getLong("expires_in")
                    val expirationTime = System.currentTimeMillis() + (expiresIn * 1000)

                    secureStorage.saveGoogleCredentials(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expirationTime = expirationTime,
                        email = email
                    )
                    true
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error exchanging auth code", e)
                false
            }
        }
    }

    private fun updateSelectedSources() {
        val sources = mutableSetOf<String>()
        if (binding.localSourceSwitch.isChecked) sources.add("local")
        if (binding.googleSourceSwitch.isChecked) sources.add("google_photos")
        viewModel.updateSources(sources)
    }

    private fun setupHelp() {
        binding.helpButton.setOnClickListener {
            Snackbar.make(
                binding.root,
                R.string.source_selection_help,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isAuthenticationInProgress = false
        googleSignInClient = null
        _binding = null
    }

    override fun isValid(): Boolean {
        return viewModel.isValid()
    }

    override fun getTitle(): String {
        return getString(R.string.select_photo_sources)
    }

    override fun getDescription(): String {
        return getString(R.string.photo_sources_explanation)
    }

    private fun observeSelections() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedSources.collect { sources ->
                binding.localSourceSwitch.isChecked = sources.contains("local")
                binding.googleSourceSwitch.isChecked = sources.contains("google_photos")
            }
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}