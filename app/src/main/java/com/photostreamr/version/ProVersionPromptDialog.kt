package com.photostreamr.version

import android.app.Dialog
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

    companion object {
        private const val ARG_FEATURE = "feature"

        fun newInstance(feature: FeatureManager.Feature): ProVersionPromptDialog {
            return ProVersionPromptDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_FEATURE, feature)
                }
            }
        }
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

        // Initialize with loading state
        updateLoadingState(true)

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
            }
        }

        // Set up buttons
        upgradeButton = view.findViewById<Button>(R.id.upgrade_button)
        cancelButton = view.findViewById<Button>(R.id.cancel_button)

        upgradeButton.setOnClickListener {
            // Show immediate visual feedback
            updateLoadingState(true)
            upgradeButton.isEnabled = false

            // Add a small delay to ensure UI updates before launching billing flow
            Handler(Looper.getMainLooper()).postDelayed({
                initiateProPurchase()
            }, 100)
        }

        cancelButton.setOnClickListener {
            dismiss()
        }

        // Observe billing repository for price updates
        observeBillingRepository()

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
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
        // Get the price from billing repository
        val price = billingRepository.getProductPrice()
        if (price != null) {
            updatePrice(price)
        } else {
            // If price isn't immediately available, observe it
            lifecycleScope.launch {
                billingRepository.billingConnectionState.observe(this@ProVersionPromptDialog) { state ->
                    if (state is BillingRepository.BillingConnectionState.Connected) {
                        val updatedPrice = billingRepository.getProductPrice()
                        if (updatedPrice != null) {
                            updatePrice(updatedPrice)
                        }
                    }
                }

                billingRepository.purchaseStatus.collectLatest { status ->
                    when (status) {
                        is BillingRepository.PurchaseStatus.Purchased -> {
                            Timber.d("Purchase completed successfully")
                            appVersionManager.refreshVersionState()
                            updateLoadingState(false)
                            dismiss()
                        }
                        is BillingRepository.PurchaseStatus.Failed -> {
                            Timber.e("Purchase failed: ${status.billingResult.debugMessage}")
                            updateLoadingState(false)
                            showError("Purchase could not be completed.")
                        }
                        is BillingRepository.PurchaseStatus.Invalid -> {
                            Timber.e("Purchase verification failed")
                            updateLoadingState(false)
                            showError("Purchase verification failed.")
                        }
                        is BillingRepository.PurchaseStatus.Canceled -> {
                            Timber.d("Purchase was canceled by user")
                            updateLoadingState(false)
                        }
                        else -> {
                            // Other states (Pending, Unknown, NotPurchased)
                            updateLoadingState(false)
                        }
                    }
                }
            }
        }
    }

    private fun updatePrice(price: String) {
        priceTextView.text = getString(R.string.upgrade_button_with_price, price)
        updateLoadingState(false)
    }

    private fun updateLoadingState(isLoading: Boolean) {
        if (::upgradeButton.isInitialized) {
            upgradeButton.isEnabled = !isLoading
            loadingView.visibility = if (isLoading) View.VISIBLE else View.GONE
            upgradeButton.alpha = if (isLoading) 0.5f else 1.0f

            if (!isLoading && priceTextView.text.isNullOrEmpty()) {
                // Set default text if price is not available
                priceTextView.text = getString(R.string.upgrade_to_pro)
            }
        }
    }

    private fun showError(message: String) {
        val errorTextView = view?.findViewById<TextView>(R.id.error_message)
        errorTextView?.text = message
        errorTextView?.visibility = View.VISIBLE
    }

    private fun initiateProPurchase() {
        billingRepository.launchBillingFlow(requireActivity())
    }
}