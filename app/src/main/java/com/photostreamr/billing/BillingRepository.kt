package com.photostreamr.billing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.photostreamr.version.AppVersionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy

@Singleton
class BillingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val appVersionManagerLazy: Lazy<AppVersionManager>
) : PurchasesUpdatedListener, BillingClientStateListener {

    private val appVersionManager by lazy { appVersionManagerLazy.get() }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.photostreamr.ACTION_REFRESH_PURCHASES") {
                Timber.d("Received purchase refresh request")
                queryPurchases()
            }
        }
    }

    companion object {
        // Product IDs
        const val PRO_VERSION_PRODUCT_ID = "com.photostreamr.pro"
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

        context.registerReceiver(
            refreshReceiver,
            IntentFilter("com.photostreamr.ACTION_REFRESH_PURCHASES"),
            Context.RECEIVER_NOT_EXPORTED
        )
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
                    Timber.d("Product price: ${proVersionDetails?.oneTimePurchaseOfferDetails?.formattedPrice}")
                } else {
                    // Add more detailed diagnostics
                    Timber.e("No product details found for Pro version. Check that:")
                    Timber.e("1. Product ID '$PRO_VERSION_PRODUCT_ID' exactly matches what's in Google Play Console")
                    Timber.e("2. Your app is published to at least an internal testing track")
                    Timber.e("3. You're signed in with a test account that has access to the app")
                    Timber.e("4. The in-app product is active in Google Play Console")
                }
            } else {
                Timber.e("Failed to query product details - Response code: ${billingResult.responseCode}")
                Timber.e("Debug message: ${billingResult.debugMessage}")

                // Translate common error codes
                val errorMessage = when (billingResult.responseCode) {
                    BillingResponseCode.SERVICE_DISCONNECTED -> "Google Play service is disconnected"
                    BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Billing feature not supported on this device"
                    BillingResponseCode.SERVICE_UNAVAILABLE -> "Network connection is down"
                    BillingResponseCode.BILLING_UNAVAILABLE -> "Billing API version not supported"
                    BillingResponseCode.ITEM_UNAVAILABLE -> "Product is not available for purchase"
                    BillingResponseCode.DEVELOPER_ERROR -> "Invalid arguments provided to the API"
                    BillingResponseCode.ERROR -> "Fatal error during the API action"
                    BillingResponseCode.ITEM_ALREADY_OWNED -> "Item already owned"
                    BillingResponseCode.ITEM_NOT_OWNED -> "Item not owned"
                    else -> "Unknown error code: ${billingResult.responseCode}"
                }
                Timber.e("Error explanation: $errorMessage")
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
                        if (purchase.isAcknowledged) {
                            Timber.d("Pro version purchase is valid and acknowledged")
                            _purchaseStatus.value = PurchaseStatus.Purchased(purchase)
                            savePurchaseState(true)
                        } else {
                            acknowledgePurchase(purchase)
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
        try {
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
                val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)

                // Log the response code immediately
                Timber.d("Billing flow launch result: ${billingResult.responseCode} - ${billingResult.debugMessage}")

                // Show feedback to user if there's an error
                if (billingResult.responseCode != BillingResponseCode.OK) {
                    Toast.makeText(activity, "Purchase error: ${billingResult.responseCode}", Toast.LENGTH_LONG).show()
                }
            } else {
                Timber.e("Unable to launch billing flow. Product details not loaded.")
                Toast.makeText(activity, "Product details not available. Please try again later.", Toast.LENGTH_LONG).show()
                queryProductDetails() // Try to fetch product details again
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during billing flow launch")
            Toast.makeText(activity, "Error starting purchase: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        val intent = android.content.Intent("com.photostreamr.ACTION_PRO_STATUS_CHANGED")
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