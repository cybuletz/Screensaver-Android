package com.photostreamr.billing

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileWriter
import java.util.Date

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

    // Add state flow for product details
    private val _productDetailsState = MutableStateFlow<ProductDetailsState>(ProductDetailsState.NotLoaded)
    val productDetailsState: StateFlow<ProductDetailsState> = _productDetailsState.asStateFlow()

    private val _purchaseCompletedEvent = MutableSharedFlow<Unit>(replay = 0)
    val purchaseCompletedEvent: SharedFlow<Unit> = _purchaseCompletedEvent.asSharedFlow()

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()
    }

    private var proVersionDetails: ProductDetails? = null

    // Track product query state
    private var isQueryingProductDetails = false
    private var productQueryRetryCount = 0
    private val maxProductQueryRetries = 3

    init {
        Timber.d("BillingRepository initialized at ${System.currentTimeMillis()}")
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

            // Pre-warm by immediately querying product details
            queryProductDetails(isPreWarm = true)

            // Check existing purchases
            queryPurchases()
        } else {
            Timber.e("Play Billing client setup failed with code ${billingResult.responseCode}: ${billingResult.debugMessage}")
            _billingConnectionState.value = BillingConnectionState.Failed(billingResult)
            _productDetailsState.value = ProductDetailsState.Error("Billing setup failed: ${billingResult.debugMessage}")
        }
    }

    override fun onBillingServiceDisconnected() {
        Timber.w("Play Billing service disconnected")
        _billingConnectionState.value = BillingConnectionState.Disconnected
        // Reconnect on next purchase attempt
    }

    fun queryProductDetails(isPreWarm: Boolean = false) {
        if (isQueryingProductDetails) {
            Timber.d("Product details query already in progress, skipping")
            return
        }

        if (!isPreWarm) {
            // Only update state to loading if this isn't the pre-warm call
            _productDetailsState.value = ProductDetailsState.Loading
        }

        Timber.d("Querying product details for Pro version (pre-warm: $isPreWarm)")
        isQueryingProductDetails = true
        productQueryRetryCount = 0
        retryProductDetailsQuery(isPreWarm)
    }

    private fun retryProductDetailsQuery(isPreWarm: Boolean = false) {
        if (productQueryRetryCount >= maxProductQueryRetries) {
            Timber.e("Failed to query product details after $maxProductQueryRetries attempts")
            isQueryingProductDetails = false
            _productDetailsState.value = ProductDetailsState.Error("Failed after $maxProductQueryRetries attempts")
            broadcastProductDetailsUpdate(false)
            return
        }

        if (billingClient.connectionState != BillingClient.ConnectionState.CONNECTED) {
            Timber.w("Billing client not connected, reconnecting...")
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingResponseCode.OK) {
                        Timber.d("Billing client reconnected, querying product details")
                        executeProductDetailsQuery(isPreWarm)
                    } else {
                        Timber.e("Failed to reconnect billing client: ${billingResult.debugMessage}")
                        productQueryRetryCount++
                        scheduleProductDetailsRetry(isPreWarm)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    Timber.w("Billing service disconnected during product details query")
                    productQueryRetryCount++
                    scheduleProductDetailsRetry(isPreWarm)
                }
            })
        } else {
            executeProductDetailsQuery(isPreWarm)
        }
    }

    private fun executeProductDetailsQuery(isPreWarm: Boolean = false) {
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
                    isQueryingProductDetails = false
                    _productDetailsState.value = ProductDetailsState.Loaded(proVersionDetails!!)
                    broadcastProductDetailsUpdate(true)
                } else {
                    // No product details found
                    Timber.e("No product details found for Pro version. Response code OK but empty list.")
                    productQueryRetryCount++
                    _productDetailsState.value = ProductDetailsState.Error("No product details found")
                    scheduleProductDetailsRetry(isPreWarm)
                }
            } else {
                // Error querying product details
                Timber.e("Failed to query product details - Response code: ${billingResult.responseCode}")
                Timber.e("Debug message: ${billingResult.debugMessage}")
                productQueryRetryCount++
                _productDetailsState.value = ProductDetailsState.Error("Error ${billingResult.responseCode}: ${billingResult.debugMessage}")
                scheduleProductDetailsRetry(isPreWarm)
            }
        }
    }

    private fun scheduleProductDetailsRetry(isPreWarm: Boolean = false) {
        // Exponential backoff for retries
        val delayMillis = (1000L * (1 shl productQueryRetryCount)).coerceAtMost(10000L) // Max 10 seconds
        Timber.d("Scheduling product details retry #${productQueryRetryCount + 1} in ${delayMillis}ms")

        Handler(Looper.getMainLooper()).postDelayed({
            retryProductDetailsQuery(isPreWarm)
        }, delayMillis)
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
            Timber.d("Purchase update received - processing ${purchases.size} purchases")

            // First check if any purchase is for our product and in purchased state
            val hasPurchase = purchases.any {
                it.products.contains(PRO_VERSION_PRODUCT_ID) &&
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
            }

            // If we have a purchase, emit Purchased status immediately to prevent race conditions
            if (hasPurchase) {
                // Find the purchase
                val purchase = purchases.first {
                    it.products.contains(PRO_VERSION_PRODUCT_ID) &&
                            it.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                // Update status before full processing to avoid UI flicker
                _purchaseStatus.value = PurchaseStatus.Purchased(purchase)
            }

            // Now process purchases normally
            processPurchases(purchases)

            // After completed purchase, do an additional refresh after a short delay
            if (purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }) {
                Handler(Looper.getMainLooper()).postDelayed({
                    queryPurchases()
                }, 1000) // 1 second delay
            }
        } else if (billingResult.responseCode == BillingResponseCode.USER_CANCELED) {
            Timber.d("User canceled the purchase")
            _purchaseStatus.value = PurchaseStatus.Canceled
        } else if (billingResult.responseCode == BillingResponseCode.ITEM_ALREADY_OWNED) {
            // Important - handle already owned case explicitly
            Timber.d("Item already owned, querying purchases to confirm")
            queryPurchases()
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
                            // Emit completion event
                            coroutineScope.launch {
                                _purchaseCompletedEvent.emit(Unit)
                            }
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
            // First check our current state
            val currentState = _productDetailsState.value

            if (currentState is ProductDetailsState.Loaded) {
                val productDetails = currentState.productDetails

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
                    _purchaseStatus.value = PurchaseStatus.Failed(billingResult)
                    Toast.makeText(activity, "Purchase error: ${getReadableErrorMessage(billingResult.responseCode)}", Toast.LENGTH_LONG).show()
                }
            } else if (proVersionDetails != null) {
                // Fallback to the previous implementation if state is inconsistent
                Timber.w("Using fallback product details because state is $currentState")

                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(proVersionDetails!!)
                        .build()
                )

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()

                val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)

                if (billingResult.responseCode != BillingResponseCode.OK) {
                    _purchaseStatus.value = PurchaseStatus.Failed(billingResult)
                    Toast.makeText(activity, "Purchase error: ${getReadableErrorMessage(billingResult.responseCode)}", Toast.LENGTH_LONG).show()
                }
            } else {
                Timber.e("Unable to launch billing flow. Product details not loaded. State: $currentState")
                _purchaseStatus.value = PurchaseStatus.Failed(
                    BillingResult.newBuilder()
                        .setResponseCode(BillingResponseCode.ERROR)
                        .setDebugMessage("Product details not available")
                        .build()
                )
                Toast.makeText(activity, "Product details not available. Please try again later.", Toast.LENGTH_LONG).show()
                // Force a new query
                queryProductDetails()
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception during billing flow launch")
            _purchaseStatus.value = PurchaseStatus.Failed(
                BillingResult.newBuilder()
                    .setResponseCode(BillingResponseCode.ERROR)
                    .setDebugMessage("Exception: ${e.message}")
                    .build()
            )
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
        val intent = Intent("com.photostreamr.ACTION_PRO_STATUS_CHANGED")
        intent.putExtra("is_pro_version", isPro)
        context.sendBroadcast(intent)
    }

    private fun broadcastProductDetailsUpdate(success: Boolean) {
        val intent = Intent("com.photostreamr.ACTION_PRODUCT_DETAILS_UPDATE")
        intent.putExtra("success", success)
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

    private fun getReadableErrorMessage(responseCode: Int): String {
        return when (responseCode) {
            BillingResponseCode.SERVICE_DISCONNECTED -> "Google Play service is disconnected"
            BillingResponseCode.FEATURE_NOT_SUPPORTED -> "Billing feature not supported on this device"
            BillingResponseCode.SERVICE_UNAVAILABLE -> "Network connection is down"
            BillingResponseCode.BILLING_UNAVAILABLE -> "Billing API version not supported"
            BillingResponseCode.ITEM_UNAVAILABLE -> "Product is not available for purchase"
            BillingResponseCode.DEVELOPER_ERROR -> "Invalid arguments provided to the API"
            BillingResponseCode.ERROR -> "Fatal error during the API action"
            BillingResponseCode.ITEM_ALREADY_OWNED -> "Item already owned"
            BillingResponseCode.ITEM_NOT_OWNED -> "Item not owned"
            else -> "Unknown error code: $responseCode"
        }
    }

    // Add diagnostic logging for production troubleshooting
    fun enableDetailedLogging(isEnabled: Boolean) {
        if (isEnabled) {
            // Log to a file that you can retrieve later
            val logFile = File(context.getExternalFilesDir(null), "billing_log.txt")
            try {
                FileWriter(logFile, true).use { writer ->
                    writer.append("=== Billing log started at ${Date()} ===\n")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to create log file")
            }
        }
    }

    private fun logToFile(message: String) {
        try {
            val logFile = File(context.getExternalFilesDir(null), "billing_log.txt")
            FileWriter(logFile, true).use { writer ->
                writer.append("${Date()}: $message\n")
            }
        } catch (e: Exception) {
            // Silent fail
        }
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

    // Add new sealed class for product details state
    sealed class ProductDetailsState {
        object NotLoaded : ProductDetailsState()
        object Loading : ProductDetailsState()
        data class Loaded(val productDetails: ProductDetails) : ProductDetailsState()
        data class Error(val message: String) : ProductDetailsState()
    }
}