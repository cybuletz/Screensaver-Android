package com.photostreamr.version

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.photostreamr.R
import com.photostreamr.billing.BillingRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class ProVersionPromptDialog : DialogFragment() {

    @Inject
    lateinit var billingRepository: BillingRepository

    @Inject
    lateinit var appVersionManager: AppVersionManager

    private lateinit var upgradeButton: Button
    private lateinit var cancelButton: Button
    private lateinit var priceTextView: TextView
    private lateinit var loadingView: View
    private lateinit var errorTextView: TextView

    // Add product details receiver
    private var productDetailsReceiver: BroadcastReceiver? = null
    private var isReturningFromPurchaseFlow = false


    // Add state flow collector
    private var productDetailsStateCollector: Job? = null
    private var purchaseStatusCollector: Job? = null
    private var versionStateCollector: Job? = null

    companion object {
        private const val ARG_FEATURE = "feature"
        private const val PRODUCT_LOAD_TIMEOUT = 5000L // 5 second timeout

        fun newInstance(feature: FeatureManager.Feature): ProVersionPromptDialog {
            return ProVersionPromptDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_FEATURE, feature)
                }
            }
        }

        // New method for creating a generic instance without a specific feature
        fun newInstance(): ProVersionPromptDialog {
            return ProVersionPromptDialog().apply {
                // No arguments means it will use the default feature (usually MUSIC or whatever you define as default)
                // When no feature is provided, the dialog will show generic PRO upgrade information
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force a product details refresh before the dialog UI is created
        billingRepository.queryProductDetails()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val feature = arguments?.getSerializable(ARG_FEATURE) as? FeatureManager.Feature
            ?: FeatureManager.Feature.MUSIC

        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pro_version_prompt, null)

        // Set feature-specific content
        val titleText = view.findViewById<TextView>(R.id.pro_feature_title)
        val descriptionText = view.findViewById<TextView>(R.id.pro_feature_description)
        val featureImage = view.findViewById<ImageView>(R.id.pro_feature_image)
        priceTextView = view.findViewById(R.id.price_text)
        loadingView = view.findViewById(R.id.loading_indicator)
        errorTextView = view.findViewById(R.id.error_message)

        // Initialize with loading state
        updateLoadingState(true)
        errorTextView.visibility = View.GONE

        // Add timeout for product loading
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAdded && !isRemoving && priceTextView.text.isNullOrEmpty() && loadingView.visibility == View.VISIBLE) {
                // If still loading after timeout, reset UI and show error
                updateLoadingState(false)
                showError("Failed to load product. Please try again.")
            }
        }, PRODUCT_LOAD_TIMEOUT)

        // Check if this was opened from the general upgrade button in settings
        val fromUpgradeButton = tag == "pro_version_prompt" && feature == FeatureManager.Feature.WIDGETS

        if (fromUpgradeButton) {
            // Generic Pro version content for the upgrade button
            titleText.text = "Upgrade to Pro"
            descriptionText.text = "Unlock all premium features including background music, custom widgets, and enhanced privacy controls. Enjoy the full experience with a one-time purchase."
            featureImage.setImageResource(R.drawable.premium)
        } else {
            // Feature-specific content for other cases
            when (feature) {
                FeatureManager.Feature.MUSIC -> {
                    titleText.text = getString(R.string.music_feature_title)
                    descriptionText.text = getString(R.string.music_feature_description)
                    featureImage.setImageResource(R.drawable.ic_music_pref)
                }
                FeatureManager.Feature.WIDGETS -> {
                    titleText.text = getString(R.string.widgets_feature_title)
                    descriptionText.text = getString(R.string.widgets_feature_description)
                    featureImage.setImageResource(R.drawable.ic_widgets_preview)
                }
                FeatureManager.Feature.SECURITY -> {
                    titleText.text = getString(R.string.security_feature_title)
                    descriptionText.text = getString(R.string.security_feature_description)
                    featureImage.setImageResource(R.drawable.ic_security)
                }
                else -> {
                    // Default case
                    titleText.text = getString(R.string.pro_feature_title)
                    descriptionText.text = getString(R.string.pro_feature_description)
                    featureImage.setImageResource(R.drawable.premium)
                }
            }
        }

        // Set up buttons
        upgradeButton = view.findViewById<Button>(R.id.upgrade_button)
        cancelButton = view.findViewById<Button>(R.id.cancel_button)

        upgradeButton.setOnClickListener {
            handleUpgradeButtonClick()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        // Register broadcast receiver for product details updates
        productDetailsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.photostreamr.ACTION_PRODUCT_DETAILS_UPDATE") {
                    val success = intent.getBooleanExtra("success", false)
                    if (!success && isAdded && !isRemoving) {
                        updateLoadingState(false)
                        showError("Failed to load product. Please try again.")
                    }
                }
            }
        }

        requireContext().registerReceiver(
            productDetailsReceiver,
            IntentFilter("com.photostreamr.ACTION_PRODUCT_DETAILS_UPDATE"),
            Context.RECEIVER_NOT_EXPORTED
        )

        // Observe billing repository state flows
        observeBillingRepository()

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    private fun handleUpgradeButtonClick() {
        // Show immediate visual feedback
        updateLoadingState(true)
        upgradeButton.isEnabled = false

        // Clear any previous errors
        errorTextView.visibility = View.GONE

        // Check current product details state
        when (val currentState = billingRepository.productDetailsState.value) {
            is BillingRepository.ProductDetailsState.Loaded -> {
                // Product details ready, proceed with purchase
                Timber.d("Product details loaded, launching billing flow")
                initiateProPurchase()
            }
            is BillingRepository.ProductDetailsState.Loading -> {
                // Already loading, wait a moment then check again
                Timber.d("Product details still loading, waiting briefly...")
                Handler(Looper.getMainLooper()).postDelayed({
                    if (billingRepository.productDetailsState.value is BillingRepository.ProductDetailsState.Loaded) {
                        initiateProPurchase()
                    } else {
                        updateLoadingState(false)
                        showError("Product details not ready yet. Please try again.")
                    }
                }, 1500) // Wait 1.5 seconds and try again
            }
            else -> {
                // Error or not loaded state
                Timber.w("Product details not available (state: $currentState), requesting refresh")
                updateLoadingState(false)
                showError("Product details not available. Please try again in a moment.")
                billingRepository.queryProductDetails() // Request fresh details
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if already purchased whenever the dialog is shown
        if (billingRepository.isPurchased()) {
            Timber.d("Product already purchased, closing dialog")
            dismiss()
            return
        }

        // Force a purchase status check
        billingRepository.verifyPurchaseStatus()
    }

    private fun observeBillingRepository() {
        // Observe product details state flow
        productDetailsStateCollector = lifecycleScope.launch {
            billingRepository.productDetailsState.collectLatest { state ->
                when (state) {
                    is BillingRepository.ProductDetailsState.Loaded -> {
                        Timber.d("Product details loaded: ${state.productDetails.name}")
                        updatePrice(state.productDetails.oneTimePurchaseOfferDetails?.formattedPrice ?: "")
                        updateLoadingState(false)
                    }
                    is BillingRepository.ProductDetailsState.Loading -> {
                        Timber.d("Product details loading...")
                        updateLoadingState(true)
                    }
                    is BillingRepository.ProductDetailsState.Error -> {
                        Timber.e("Product details error: ${state.message}")
                        updateLoadingState(false)
                        showError("Failed to load product: ${state.message}")
                    }
                    is BillingRepository.ProductDetailsState.NotLoaded -> {
                        // Initial state, request product details
                        Timber.d("Product details not loaded, requesting...")
                        billingRepository.queryProductDetails()
                    }
                }
            }
        }

        // Legacy approach as fallback
        val price = billingRepository.getProductPrice()
        if (price != null) {
            updatePrice(price)
        }

        // Observe purchase status flow
        purchaseStatusCollector = lifecycleScope.launch {
            billingRepository.purchaseStatus.collectLatest { status ->
                when (status) {
                    is BillingRepository.PurchaseStatus.Purchased -> {
                        Timber.d("Purchase completed successfully")
                        // Flag that we're returning from purchase flow
                        isReturningFromPurchaseFlow = true

                        // Force refresh and ensure dialog dismisses
                        appVersionManager.refreshVersionState()
                        updateLoadingState(false)

                        // Hide any error messages that might be showing
                        if (::errorTextView.isInitialized && isAdded && !isRemoving) {
                            errorTextView.visibility = View.GONE
                        }

                        // Add delay to ensure state is updated before dismissing
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isAdded && !isRemoving) {
                                dismiss()
                            }
                        }, 500)
                    }
                    is BillingRepository.PurchaseStatus.Failed -> {
                        Timber.e("Purchase failed: ${status.billingResult.debugMessage}")
                        // Only show error if we're not in the process of returning from a successful purchase
                        if (!isReturningFromPurchaseFlow) {
                            updateLoadingState(false)
                            showError("Purchase could not be completed: ${status.billingResult.debugMessage}")
                        }
                    }
                    is BillingRepository.PurchaseStatus.Canceled -> {
                        Timber.d("Purchase was canceled by user")
                        isReturningFromPurchaseFlow = false
                        updateLoadingState(false)
                    }
                    is BillingRepository.PurchaseStatus.Pending,
                    is BillingRepository.PurchaseStatus.Invalid,
                    is BillingRepository.PurchaseStatus.Unknown -> {
                        // If we already know we're returning from purchase flow, ignore these states
                        if (!isReturningFromPurchaseFlow) {
                            updateLoadingState(false)
                        }
                    }
                    // This case ensures we handle when payment is successful
                    is BillingRepository.PurchaseStatus.NotPurchased -> {
                        // Only update UI if not purchased and dialog still showing
                        // and we're not returning from purchase flow
                        if (!isReturningFromPurchaseFlow && isAdded && !isRemoving) {
                            updateLoadingState(false)
                        }
                    }
                }
            }
        }

        // Observe version state changes
        versionStateCollector = lifecycleScope.launch {
            appVersionManager.versionState.collectLatest { state ->
                if (state is AppVersionManager.VersionState.Pro && isAdded && !isRemoving) {
                    // Pro state detected, dismiss dialog
                    dismiss()
                }
            }
        }

        lifecycleScope.launch {
            billingRepository.purchaseCompletedEvent.collect {
                Timber.d("Received purchase completed event")
                // Clear any error message
                errorTextView.visibility = View.GONE
                // Show success message briefly if needed
                // Then dismiss
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded && !isRemoving) {
                        dismiss()
                    }
                }, 300)
            }
        }
    }

    private fun updatePrice(price: String) {
        if (isAdded && !isRemoving) {
            if (price.isNotEmpty()) {
                priceTextView.text = getString(R.string.upgrade_button_with_price, price)
                updateLoadingState(false)
            } else {
                priceTextView.text = getString(R.string.upgrade_to_pro)
            }
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        if (!::upgradeButton.isInitialized || !isAdded || isRemoving) {
            return
        }

        upgradeButton.isEnabled = !isLoading
        loadingView.visibility = if (isLoading) View.VISIBLE else View.GONE
        upgradeButton.alpha = if (isLoading) 0.5f else 1.0f

        if (!isLoading && priceTextView.text.isNullOrEmpty()) {
            // Set default text if price is not available
            priceTextView.text = getString(R.string.upgrade_to_pro)
        }
    }

    private fun showError(message: String) {
        if (!isAdded || isRemoving) {
            return
        }

        errorTextView.text = message
        errorTextView.visibility = View.VISIBLE
    }

    private fun initiateProPurchase() {
        if (!isAdded || isRemoving) {
            return
        }

        billingRepository.launchBillingFlow(requireActivity())
    }

    override fun onDestroyView() {
        // Cancel all state collectors
        productDetailsStateCollector?.cancel()
        purchaseStatusCollector?.cancel()
        versionStateCollector?.cancel()

        // Unregister the broadcast receiver
        if (productDetailsReceiver != null) {
            try {
                requireContext().unregisterReceiver(productDetailsReceiver)
                productDetailsReceiver = null
            } catch (e: Exception) {
                // Receiver might not be registered, ignore
                Timber.e(e, "Error unregistering product details receiver")
            }
        }

        super.onDestroyView()
    }
}