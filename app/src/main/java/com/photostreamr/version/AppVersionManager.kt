package com.photostreamr.version

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.preference.PreferenceManager
import com.photostreamr.billing.BillingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppVersionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val billingRepository: BillingRepository,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val KEY_IS_PRO_VERSION = "is_pro_version"
        private const val KEY_PRO_VERSION_VERIFICATION = "pro_version_verification"
        private const val KEY_PRO_PURCHASE_TIME = "pro_purchase_time"
        private const val KEY_LAST_AD_SHOWN_TIME = "last_ad_shown_time"
        private const val DEFAULT_AD_INTERVAL = 10 * 60 * 1000L // 10 minutes in milliseconds
        private const val KEY_DEV_ADS_DISABLED = "development_ads_disabled"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "ProVersionEncryptionKey"
        private const val ENCRYPTION_BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val ENCRYPTION_PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val ENCRYPTION_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val GCM_TAG_LENGTH = 128
    }

    private val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    private val securePreferences: SharedPreferences by lazy {
        context.getSharedPreferences("secure_app_version", Context.MODE_PRIVATE)
    }

    private val _versionState = MutableStateFlow<VersionState>(VersionState.Free)
    val versionState: StateFlow<VersionState> = _versionState.asStateFlow()

    private val purchaseStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.photostreamr.ACTION_PRO_STATUS_CHANGED") {
                val isPro = intent.getBooleanExtra("is_pro_version", false)
                updateProStatusFromBilling(isPro)
            }
        }
    }

    init {
        // Register for purchase status changes
        context.registerReceiver(
            purchaseStatusReceiver,
            IntentFilter("com.photostreamr.ACTION_PRO_STATUS_CHANGED"),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Initialize crypto if needed
        initializeEncryption()

        // Load stored version state
        loadVersionState()

        // Monitor billing repository status
        coroutineScope.launch {
            billingRepository.purchaseStatus.collectLatest { status ->
                when (status) {
                    is BillingRepository.PurchaseStatus.Purchased -> {
                        updateProStatusFromBilling(true)
                    }
                    is BillingRepository.PurchaseStatus.NotPurchased,
                    is BillingRepository.PurchaseStatus.Invalid,
                    is BillingRepository.PurchaseStatus.Failed -> {
                        // Only downgrade if we're not in development testing mode
                        if (!isDevelopmentTestingMode()) {
                            updateProStatusFromBilling(false)
                        }
                    }
                    else -> {
                        // For other states (Pending, Canceled, Unknown), don't change the current state
                    }
                }
            }
        }

        // Verify purchase status on startup
        billingRepository.verifyPurchaseStatus()
    }

    private fun updateProStatusFromBilling(isPro: Boolean) {
        if (isPro) {
            val timestamp = System.currentTimeMillis()
            val verificationToken = generateVerificationToken()

            // Store encrypted data
            preferences.edit()
                .putBoolean(KEY_IS_PRO_VERSION, true)
                .putLong(KEY_PRO_PURCHASE_TIME, timestamp)
                .apply()

            // Store verification token in secure storage
            securePreferences.edit()
                .putString(KEY_PRO_VERSION_VERIFICATION, encryptVerificationToken(verificationToken))
                .apply()

            _versionState.value = VersionState.Pro
        } else {
            // Only clear pro status if we're not in development mode
            if (!isDevelopmentTestingMode()) {
                preferences.edit()
                    .putBoolean(KEY_IS_PRO_VERSION, false)
                    .remove(KEY_PRO_PURCHASE_TIME)
                    .apply()

                securePreferences.edit()
                    .remove(KEY_PRO_VERSION_VERIFICATION)
                    .apply()

                _versionState.value = VersionState.Free
            }
        }
    }

    private fun loadVersionState() {
        try {
            // Check for development testing mode first
            if (isDevelopmentTestingMode()) {
                val isDeveloperPro = preferences.getBoolean("developer_pro_mode", false)
                _versionState.value = if (isDeveloperPro) VersionState.Pro else VersionState.Free
                return
            }

            // Normal production operation - verify stored status
            val storedIsPro = preferences.getBoolean(KEY_IS_PRO_VERSION, false)
            val verificationEncrypted = securePreferences.getString(KEY_PRO_VERSION_VERIFICATION, null)

            if (storedIsPro && verificationEncrypted != null) {
                try {
                    // Try to decrypt the verification token
                    val decryptedToken = decryptVerificationToken(verificationEncrypted)

                    // Verify the token format (simple UUID check)
                    if (isValidUUID(decryptedToken)) {
                        _versionState.value = VersionState.Pro
                    } else {
                        Timber.w("Invalid verification token format, resetting to Free status")
                        _versionState.value = VersionState.Free
                        preferences.edit().putBoolean(KEY_IS_PRO_VERSION, false).apply()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to decrypt verification token")
                    _versionState.value = VersionState.Free
                    preferences.edit().putBoolean(KEY_IS_PRO_VERSION, false).apply()
                }
            } else {
                _versionState.value = VersionState.Free
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading version state")
            _versionState.value = VersionState.Free
        }
    }

    fun refreshVersionState() {
        // Force a billing check
        coroutineScope.launch {
            try {
                // First verify purchase status through billing
                billingRepository.verifyPurchaseStatus()

                // Check billing status after a brief delay
                delay(500)

                if (billingRepository.isPurchased()) {
                    updateProStatusFromBilling(true)
                }
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing version state")
            }
        }
    }

    fun shouldSkipVerificationInTestMode(): Boolean {
        if (!isDebugBuild() || !isDevelopmentTestingMode()) {
            return false // Only skip in debug build and test mode
        }

        val devPrefs = context.getSharedPreferences("developer_settings", Context.MODE_PRIVATE)
        return devPrefs.getBoolean("skip_purchase_verification", true)
    }

    fun isProVersion(): Boolean = _versionState.value is VersionState.Pro

    fun enableDevelopmentTesting() {
        if (isDebugBuild()) {
            preferences.edit()
                .putBoolean("development_testing_mode", true)
                .apply()

            // Log the change for debugging
            Timber.d("Development testing mode enabled")
        }
    }

    fun setProVersionForDevelopment(isPro: Boolean) {
        // This should only work in debug builds
        if (isDebugBuild()) {
            // Use both preferences and state flow for immediate effect
            preferences.edit()
                .putBoolean("developer_pro_mode", isPro)
                .putBoolean("development_testing_mode", true)
                .apply()

            // Update state immediately
            _versionState.value = if (isPro) VersionState.Pro else VersionState.Free

            // Log the change for debugging
            Timber.d("Development testing mode enabled: Pro=$isPro")
        }
    }

    fun disableDevelopmentTesting() {
        if (isDebugBuild()) {
            preferences.edit()
                .putBoolean("development_testing_mode", false)
                .apply()

            // Reload actual state from billing
            billingRepository.verifyPurchaseStatus()
            loadVersionState()
            Timber.d("Development testing mode disabled")
        }
    }

    fun isDevelopmentTestingMode(): Boolean {
        return isDebugBuild() && preferences.getBoolean("development_testing_mode", false)
    }

    fun isDebugBuild(): Boolean {
        return try {
            val applicationInfo = context.applicationInfo
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            Timber.e(e, "Error checking debug status")
            false
        }
    }

    fun setDevAdsDisabled(disabled: Boolean) {
        if (isDebugBuild()) {
            preferences.edit()
                .putBoolean(KEY_DEV_ADS_DISABLED, disabled)
                .apply()

            // Log the change
            Timber.d("Development ads disabled: $disabled")
        }
    }

    fun areDevAdsDisabled(): Boolean {
        return isDebugBuild() &&
                isDevelopmentTestingMode() &&
                preferences.getBoolean(KEY_DEV_ADS_DISABLED, false)
    }



    fun shouldShowAd(): Boolean {
        // Don't show ads for Pro users (existing behavior)
        // Don't show ads if in development mode with ads explicitly disabled (new behavior)
        if (isProVersion() || areDevAdsDisabled()) return false

        // Regular ad timing logic for free users (existing behavior)
        val currentTime = System.currentTimeMillis()
        val lastAdShownTime = preferences.getLong(KEY_LAST_AD_SHOWN_TIME, 0L)
        val adInterval = preferences.getLong("ad_interval", DEFAULT_AD_INTERVAL)

        return currentTime - lastAdShownTime >= adInterval
    }

    fun updateLastAdShownTime() {
        preferences.edit().putLong(KEY_LAST_AD_SHOWN_TIME, System.currentTimeMillis()).apply()
    }

    fun getAdInterval(): Long {
        return preferences.getLong("ad_interval", DEFAULT_AD_INTERVAL)
    }

    fun setAdInterval(intervalMillis: Long) {
        preferences.edit().putLong("ad_interval", intervalMillis).apply()
    }

    // Encryption related methods
    private fun initializeEncryption() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    ENCRYPTION_ALGORITHM,
                    ANDROID_KEYSTORE
                )

                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    ENCRYPTION_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(ENCRYPTION_BLOCK_MODE)
                    .setEncryptionPaddings(ENCRYPTION_PADDING)
                    .setRandomizedEncryptionRequired(true)
                    .build()

                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()

                Timber.d("Created new encryption key for Pro version verification")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize encryption")
        }
    }

    private fun getEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) as SecretKey
    }

    private fun encryptVerificationToken(token: String): String {
        val cipher = Cipher.getInstance("$ENCRYPTION_ALGORITHM/$ENCRYPTION_BLOCK_MODE/$ENCRYPTION_PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())

        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))

        // Concatenate IV and encrypted data
        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decryptVerificationToken(encryptedData: String): String {
        val combined = Base64.getDecoder().decode(encryptedData)

        // Extract IV
        val iv = ByteArray(12) // GCM IV size
        System.arraycopy(combined, 0, iv, 0, iv.size)

        // Extract encrypted data
        val encryptedBytes = ByteArray(combined.size - iv.size)
        System.arraycopy(combined, iv.size, encryptedBytes, 0, encryptedBytes.size)

        val cipher = Cipher.getInstance("$ENCRYPTION_ALGORITHM/$ENCRYPTION_BLOCK_MODE/$ENCRYPTION_PADDING")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey(), spec)

        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, StandardCharsets.UTF_8)
    }

    private fun generateVerificationToken(): String {
        return UUID.randomUUID().toString()
    }

    private fun isValidUUID(s: String): Boolean {
        return try {
            UUID.fromString(s)
            true
        } catch (e: Exception) {
            false
        }
    }

    sealed class VersionState {
        object Free : VersionState()
        object Pro : VersionState()
    }
}