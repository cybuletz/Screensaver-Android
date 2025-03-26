package com.example.screensaver.billing

import android.app.Activity
import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coroutineScope: CoroutineScope
) : PurchasesUpdatedListener, BillingClientStateListener {

    companion object {
        // Product IDs
        const val PRO_VERSION_PRODUCT_ID = "com.example.screensaver.pro"

        // Verification salts (change these to random strings in production)
        private const val VERIFICATION_SALT_1 = "a1b2c3d4e5f6g7h8i9j0"
        private const val VERIFICATION_SALT_2 = "z9y8x7w6v5u4t3s2r1q0"
    }

    private val _purchaseStatus = MutableStateFlow<PurchaseStatus>(PurchaseStatus.NotPurchased)
    val purchaseStatus: StateFlow<PurchaseStatus> = _purchaseStatus.asStateFlow()

    private val _billingConnectionState = MutableLiveData<BillingConnectionState>()
    val billingConnectionState: LiveData<BillingConnectionState> = _billingConnectionState

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
    }

    private var proVersionDetails: ProductDetails? = null

    init {
        connectToPlayBilling()
    }

    fun connectToPlayBilling() {
        Timber.d("Connecting to Play Billing service...")
        _billingConnectionState.value = BillingConnectionState.Connecting
        billingClient.startConnection(this)
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingResponseCode.OK) {
            Timber.d("Play Billing client connected successfully")
            _billingConnectionState.value = BillingConnectionState.Connected
            // Query product details once connected
            queryProductDetails()
            // Check existing purchases
            queryPurchases()
        } else {
            Timber.e("Play Billing client setup failed with code ${billingResult.responseCode}: ${billingResult.debugMessage}")
            _billingConnectionState.value = BillingConnectionState.Failed(billingResult)
        }
    }

    override fun onBillingServiceDisconnected() {
        Timber.w("Play Billing service disconnected")
        _billingConnectionState.value = BillingConnectionState.Disconnected
        // Reconnect on next purchase attempt
    }

    private fun queryProductDetails() {
        Timber.d("Querying product details for Pro version")
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_VERSION_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                if (productDetailsList.isNotEmpty()) {
                    proVersionDetails = productDetailsList[0]
                    Timber.d("Pro version product details retrieved successfully: ${proVersionDetails?.name}")
                } else {
                    Timber.e("No product details found for Pro version")
                }
            } else {
                Timber.e("Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    private fun queryPurchases() {
        if (billingClient.connectionState != BillingClient.ConnectionState.CONNECTED) {
            Timber.w("Billing client not connected, cannot query purchases")
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                processPurchases(purchasesList)
            } else {
                Timber.e("Failed to query purchases: ${billingResult.debugMessage}")
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingResponseCode.OK && !purchases.isNullOrEmpty()) {
            processPurchases(purchases)
        } else if (billingResult.responseCode == BillingResponseCode.USER_CANCELED) {
            Timber.d("User canceled the purchase")
            _purchaseStatus.value = PurchaseStatus.Canceled
        } else {
            Timber.e("Purchase failed with code ${billingResult.responseCode}: ${billingResult.debugMessage}")
            _purchaseStatus.value = PurchaseStatus.Failed(billingResult)
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        Timber.d("Processing ${purchases.size} purchases")
        for (purchase in purchases) {
            if (purchase.products.contains(PRO_VERSION_PRODUCT_ID)) {
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        if (verifyValidSignature(purchase)) {
                            if (purchase.isAcknowledged) {
                                Timber.d("Pro version purchase is valid and acknowledged")
                                _purchaseStatus.value = PurchaseStatus.Purchased(purchase)
                                savePurchaseState(true)
                            } else {
                                acknowledgePurchase(purchase)
                            }
                        } else {
                            Timber.w("Purchase signature verification failed")
                            _purchaseStatus.value = PurchaseStatus.Invalid(purchase)
                            savePurchaseState(false)
                        }
                    }
                    Purchase.PurchaseState.PENDING -> {
                        Timber.d("Purchase is pending")
                        _purchaseStatus.value = PurchaseStatus.Pending(purchase)
                        savePurchaseState(false)
                    }
                    else -> {
                        Timber.d("Purchase in unknown state: ${purchase.purchaseState}")
                        _purchaseStatus.value = PurchaseStatus.Unknown(purchase)
                        savePurchaseState(false)
                    }
                }
            }
        }

        // If no active purchases found, ensure pro state is not active
        if (purchases.none { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.contains(PRO_VERSION_PRODUCT_ID) }) {
            savePurchaseState(false)
            _purchaseStatus.value = PurchaseStatus.NotPurchased
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    Timber.d("Purchase acknowledged successfully")
                    _purchaseStatus.value = PurchaseStatus.Purchased(purchase)
                    savePurchaseState(true)
                } else {
                    Timber.e("Failed to acknowledge purchase: ${billingResult.debugMessage}")
                    _purchaseStatus.value = PurchaseStatus.Failed(billingResult)
                }
            }
        }
    }

    fun launchBillingFlow(activity: Activity) {
        val productDetails = proVersionDetails
        if (productDetails != null) {
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .build()
            )

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()

            Timber.d("Launching billing flow for Pro version")
            billingClient.launchBillingFlow(activity, billingFlowParams)
        } else {
            Timber.e("Unable to launch billing flow. Product details not loaded.")
            _purchaseStatus.value = PurchaseStatus.Failed(
                BillingResult.newBuilder()
                    .setResponseCode(BillingResponseCode.ERROR)
                    .setDebugMessage("Product details not available")
                    .build()
            )
            queryProductDetails() // Try to fetch product details again
        }
    }

    private fun verifyValidSignature(purchase: Purchase): Boolean {
        try {
            // In a production app, you would implement server-side purchase verification
            // For now, we'll use a simplified local verification
            if (purchase.purchaseToken.isEmpty()) {
                return false
            }

            // Create a token that combines purchase information with our salt values
            val expectedVerification = generateLocalVerificationToken(purchase)
            val actualVerification = computeSHA256Hash(purchase.originalJson + VERIFICATION_SALT_2)

            // Verify both tokens match
            return expectedVerification == actualVerification
        } catch (e: Exception) {
            Timber.e(e, "Error verifying purchase")
            return false
        }
    }

    private fun generateLocalVerificationToken(purchase: Purchase): String {
        val baseString = purchase.originalJson + VERIFICATION_SALT_1
        return computeSHA256Hash(baseString)
    }

    private fun computeSHA256Hash(input: String): String {
        val bytes = input.toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    private fun savePurchaseState(isPro: Boolean) {
        coroutineScope.launch {
            try {
                // Persist purchase state in two different locations with different keys
                val preferences = context.getSharedPreferences("billing_preferences", Context.MODE_PRIVATE)
                val securePreferences = context.getSharedPreferences("secure_preferences", Context.MODE_PRIVATE)

                // Save to regular preferences
                preferences.edit()
                    .putBoolean("is_pro_version", isPro)
                    .putLong("purchase_verification_time", System.currentTimeMillis())
                    .apply()

                // Save to secure preferences with different key
                securePreferences.edit()
                    .putBoolean("pro_status_verified", isPro)
                    .putLong("pro_verification_timestamp", System.currentTimeMillis())
                    .apply()

                // Broadcast the change to the version manager
                broadcastPurchaseUpdate(isPro)
            } catch (e: Exception) {
                Timber.e(e, "Failed to save purchase state")
            }
        }
    }

    private fun broadcastPurchaseUpdate(isPro: Boolean) {
        // Updating other app components about the purchase state
        val intent = android.content.Intent("com.example.screensaver.ACTION_PRO_STATUS_CHANGED")
        intent.putExtra("is_pro_version", isPro)
        context.sendBroadcast(intent)
    }

    fun isPurchased(): Boolean {
        return purchaseStatus.value is PurchaseStatus.Purchased
    }

    // Call this method to verify purchase status on app startup
    fun verifyPurchaseStatus() {
        if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
            queryPurchases()
        } else {
            connectToPlayBilling()
        }
    }

    fun getProductPrice(): String? {
        return proVersionDetails?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    sealed class PurchaseStatus {
        object NotPurchased : PurchaseStatus()
        object Canceled : PurchaseStatus()
        data class Pending(val purchase: Purchase) : PurchaseStatus()
        data class Purchased(val purchase: Purchase) : PurchaseStatus()
        data class Failed(val billingResult: BillingResult) : PurchaseStatus()
        data class Invalid(val purchase: Purchase) : PurchaseStatus()
        data class Unknown(val purchase: Purchase) : PurchaseStatus()
    }

    sealed class BillingConnectionState {
        object Connecting : BillingConnectionState()
        object Connected : BillingConnectionState()
        object Disconnected : BillingConnectionState()
        data class Failed(val billingResult: BillingResult) : BillingConnectionState()
    }
}