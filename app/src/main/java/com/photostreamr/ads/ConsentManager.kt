package com.photostreamr.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentForm
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.photostreamr.BuildConfig

@Singleton
class ConsentManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ConsentManager"
    }

    private lateinit var consentInformation: ConsentInformation
    private var consentForm: ConsentForm? = null

    // Observable consent state for other components to react to
    val consentState = MutableLiveData<ConsentState>()

    enum class ConsentState {
        UNKNOWN,
        REQUIRED,
        OBTAINED,
        NOT_REQUIRED,
        ERROR
    }

    init {
        consentState.value = ConsentState.UNKNOWN
    }

    /**
     * Request consent information and determine if consent is required
     * @param activity Current activity context for displaying the form
     * @param showFormIfRequired Whether to automatically show the form if consent is required
     * @param callback Called with the result of the consent check
     */
    fun requestConsentInfo(
        activity: Activity,
        showFormIfRequired: Boolean = true,
        callback: ((isConsentRequired: Boolean) -> Unit)? = null
    ) {
        Log.d(TAG, "Requesting consent information")

        val params = ConsentRequestParameters.Builder()

        // Add debug settings in debug builds only
        if (BuildConfig.DEBUG) {
            val debugSettings = ConsentDebugSettings.Builder(context)
                // Use EEA geography for testing
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                // Add test device IDs here if needed
                //.addTestDeviceHashedId("TEST-DEVICE-HASH-HERE")
                .build()
            params.setConsentDebugSettings(debugSettings)
            Log.d(TAG, "Debug settings enabled for consent")
        }

        // Get the consent information
        consentInformation = UserMessagingPlatform.getConsentInformation(context)

        consentInformation.requestConsentInfoUpdate(
            activity,
            params.build(),
            {
                // Success callback
                val consentStatus = consentInformation.consentStatus
                Log.d(TAG, "Consent info updated, status: $consentStatus")

                val consentRequired = consentStatus == ConsentInformation.ConsentStatus.REQUIRED

                consentState.value = when (consentStatus) {
                    ConsentInformation.ConsentStatus.REQUIRED -> ConsentState.REQUIRED
                    ConsentInformation.ConsentStatus.OBTAINED -> ConsentState.OBTAINED
                    ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentState.NOT_REQUIRED
                    else -> ConsentState.UNKNOWN
                }

                if (consentRequired && showFormIfRequired) {
                    Log.d(TAG, "Consent required, loading form")
                    loadConsentForm(activity)
                }

                callback?.invoke(consentRequired)
            },
            { error ->
                // Error callback
                Log.e(TAG, "Error requesting consent info: ${error.message}")
                consentState.value = ConsentState.ERROR
                callback?.invoke(false)
            }
        )
    }

    /**
     * Load the consent form
     */
    private fun loadConsentForm(activity: Activity) {
        UserMessagingPlatform.loadConsentForm(
            context,
            { form ->
                Log.d(TAG, "Consent form loaded successfully")
                consentForm = form

                // Show the form right away if consent is required
                if (consentInformation.consentStatus == ConsentInformation.ConsentStatus.REQUIRED) {
                    showConsentForm(activity)
                }
            },
            { error ->
                Log.e(TAG, "Error loading consent form: ${error.message}")
                consentState.value = ConsentState.ERROR
            }
        )
    }

    /**
     * Show the consent form to the user
     */
    /**
     * Show the consent form to the user
     */
    fun showConsentForm(activity: Activity) {
        // Check if form is already loaded
        if (consentForm != null) {
            // Form is loaded, show it directly
            consentForm?.show(activity) { formError ->
                if (formError != null) {
                    Log.e(TAG, "Error showing consent form: ${formError.message}")
                } else {
                    Log.d(TAG, "Consent form shown successfully")

                    // Debug the TCF preferences right after consent form is closed
                    debugTCFPreferences()

                    // Update the consent state after the form is closed
                    consentState.value = when (consentInformation.consentStatus) {
                        ConsentInformation.ConsentStatus.OBTAINED -> ConsentState.OBTAINED
                        ConsentInformation.ConsentStatus.REQUIRED -> ConsentState.REQUIRED
                        ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentState.NOT_REQUIRED
                        else -> ConsentState.UNKNOWN
                    }
                }

                // Very important: Set the form to null after it's been shown
                // This allows us to load a new form next time
                consentForm = null
            }
        } else {
            // Form is not loaded yet, show a loading indicator
            Toast.makeText(activity, "Loading consent options...", Toast.LENGTH_SHORT).show()

            // Load the form first, then show it automatically when loaded
            UserMessagingPlatform.loadConsentForm(
                activity,
                { form ->
                    Log.d(TAG, "Consent form loaded successfully")
                    consentForm = form

                    // Show the form immediately after loading
                    form.show(activity) { formError ->
                        if (formError != null) {
                            Log.e(TAG, "Error showing consent form: ${formError.message}")
                        } else {
                            Log.d(TAG, "Consent form shown successfully")

                            // Debug the TCF preferences right after consent form is closed
                            debugTCFPreferences()

                            // Update the consent state after the form is closed
                            consentState.value = when (consentInformation.consentStatus) {
                                ConsentInformation.ConsentStatus.OBTAINED -> ConsentState.OBTAINED
                                ConsentInformation.ConsentStatus.REQUIRED -> ConsentState.REQUIRED
                                ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentState.NOT_REQUIRED
                                else -> ConsentState.UNKNOWN
                            }
                        }

                        // Very important: Set the form to null after it's been shown
                        // This allows us to load a new form next time
                        consentForm = null
                    }
                },
                { error ->
                    Log.e(TAG, "Error loading consent form: ${error.message}")
                    consentState.value = ConsentState.ERROR

                    // Inform the user about the error
                    Toast.makeText(activity, "Unable to load consent options. Please try again later.", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /**
     * Check if ANY ads can be shown (personalized OR non-personalized)
     * We should return true as long as consent process is complete
     */
    fun canShowAds(): Boolean {
        if (!::consentInformation.isInitialized) {
            Log.d(TAG, "Consent information not initialized yet")
            return false
        }

        val status = consentInformation.consentStatus
        Log.d(TAG, "Checking if ads can be shown, consent status: $status")

        // We can show some form of ads as long as:
        // 1. Consent is not required, OR
        // 2. Consent has been obtained (user made a choice, even if they denied all purposes)
        return status == ConsentInformation.ConsentStatus.NOT_REQUIRED ||
                status == ConsentInformation.ConsentStatus.OBTAINED
    }

    /**
     * Check if PERSONALIZED ads can be shown
     */
    /**
     * Check if PERSONALIZED ads can be shown
     */
    fun canShowPersonalizedAds(): Boolean {
        if (!canShowAds()) {
            return false
        }

        // If consent isn't required, we can show personalized ads
        if (consentInformation.consentStatus == ConsentInformation.ConsentStatus.NOT_REQUIRED) {
            Log.d(TAG, "Personalized ads allowed: consent not required")
            return true
        }

        // The key point is to use the correct context and preferences name
        try {
            // First check: Look for UMP_consentModeValues in the UMP preferences
            val umpPrefs = context.getSharedPreferences("UMP", Context.MODE_PRIVATE)
            val consentModeValues = umpPrefs.getString("UMP_consentModeValues", "")
            Log.d(TAG, "UMP consent mode values: $consentModeValues")

            // "4444" indicates that ad personalization is allowed
            if (consentModeValues == "4444") {
                Log.d(TAG, "Personalized ads allowed: UMP_consentModeValues = 4444")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading UMP consent mode values", e)
        }

        // Second check: Look for IABTCF_PurposeConsents in default preferences
        try {
            // Try reading from default SharedPreferences first
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val purposeConsents = defaultPrefs.getString("IABTCF_PurposeConsents", "")

            Log.d(TAG, "Read TCF purpose consents from default prefs: '$purposeConsents'")

            if (purposeConsents == "11111111111") {
                Log.d(TAG, "Full consent detected in default prefs, showing personalized ads")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading TCF consent data from default prefs", e)
        }

        // Default to non-personalized ads
        Log.d(TAG, "Defaulting to non-personalized ads")
        return false
    }

    fun debugTCFPreferences() {
        try {
            // Check UMP preferences
            val umpPrefs = context.getSharedPreferences("UMP", Context.MODE_PRIVATE)
            val umpAll = umpPrefs.all

            Log.d(TAG, "===== UMP Preferences Debug =====")
            Log.d(TAG, "Number of UMP preferences: ${umpAll.size}")
            umpAll.forEach { (key, value) ->
                Log.d(TAG, "UMP[$key] = $value")
            }

            // Check default preferences
            val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val defaultAll = defaultPrefs.all.filter { (key, _) -> key.startsWith("IABTCF_") || key.startsWith("UMP_") }

            Log.d(TAG, "===== Default Preferences Debug =====")
            Log.d(TAG, "Number of TCF/UMP preferences in default: ${defaultAll.size}")
            defaultAll.forEach { (key, value) ->
                Log.d(TAG, "Default[$key] = $value")
            }

            // Check IABTCF preferences specifically
            val iabtcfPrefs = context.getSharedPreferences("IABTCF", Context.MODE_PRIVATE)
            val iabtcfAll = iabtcfPrefs.all

            Log.d(TAG, "===== IABTCF Preferences Debug =====")
            Log.d(TAG, "Number of IABTCF preferences: ${iabtcfAll.size}")
            iabtcfAll.forEach { (key, value) ->
                Log.d(TAG, "IABTCF[$key] = $value")
            }

            // Now specifically check the purpose consents key in all locations
            val umpPurposeConsents = umpPrefs.getString("IABTCF_PurposeConsents", "")
            val defaultPurposeConsents = defaultPrefs.getString("IABTCF_PurposeConsents", "")
            val iabtcfPurposeConsents = iabtcfPrefs.getString("IABTCF_PurposeConsents", "")

            Log.d(TAG, "Purpose consents in UMP: '$umpPurposeConsents'")
            Log.d(TAG, "Purpose consents in default: '$defaultPurposeConsents'")
            Log.d(TAG, "Purpose consents in IABTCF: '$iabtcfPurposeConsents'")

            // Check UMP_consentModeValues
            val umpConsentValues = umpPrefs.getString("UMP_consentModeValues", "")
            val defaultConsentValues = defaultPrefs.getString("UMP_consentModeValues", "")

            Log.d(TAG, "UMP_consentModeValues in UMP: '$umpConsentValues'")
            Log.d(TAG, "UMP_consentModeValues in default: '$defaultConsentValues'")

            Log.d(TAG, "===== End Preferences Debug =====")
        } catch (e: Exception) {
            Log.e(TAG, "Error debugging preferences", e)
        }
    }


    /**
     * Reset consent state - typically used for testing or when user requests to reset preferences
     */
    fun reset() {
        if (::consentInformation.isInitialized) {
            Log.d(TAG, "Resetting consent state")
            consentInformation.reset()
            consentState.value = ConsentState.UNKNOWN
        }
    }

    /**
     * Get current privacy options as a string for debugging
     */
    fun getPrivacyOptionsString(): String {
        if (!::consentInformation.isInitialized) return "Consent not initialized"

        return try {
            val status = when (consentInformation.consentStatus) {
                ConsentInformation.ConsentStatus.REQUIRED -> "REQUIRED"
                ConsentInformation.ConsentStatus.OBTAINED -> "OBTAINED"
                ConsentInformation.ConsentStatus.NOT_REQUIRED -> "NOT_REQUIRED"
                else -> "UNKNOWN"
            }

            "Privacy Status: $status"
        } catch (e: Exception) {
            "Error getting privacy options: ${e.message}"
        }
    }
}