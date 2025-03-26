package com.photostreamr.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import com.photostreamr.R
import com.photostreamr.utils.AppPreferences
import com.photostreamr.version.AppVersionManager
import com.photostreamr.version.FeatureManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DeveloperSettingsDialogFragment : DialogFragment() {

    @Inject
    lateinit var appVersionManager: AppVersionManager

    @Inject
    lateinit var featureManager: FeatureManager

    @Inject
    lateinit var preferences: AppPreferences

    private lateinit var proVersionSwitch: Switch
    private lateinit var testModeText: TextView
    private lateinit var versionStateText: TextView
    private lateinit var disableTestingButton: Button
    private lateinit var enableTestingButton: Button
    private lateinit var disableAdsSwitch: Switch
    private lateinit var skipVerificationSwitch: Switch



    companion object {
        fun newInstance(): DeveloperSettingsDialogFragment {
            return DeveloperSettingsDialogFragment()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_developer_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI elements
        proVersionSwitch = view.findViewById(R.id.pro_version_switch)
        testModeText = view.findViewById(R.id.test_mode_status)
        versionStateText = view.findViewById(R.id.version_state)
        disableTestingButton = view.findViewById(R.id.disable_testing_button)
        enableTestingButton = view.findViewById(R.id.enable_testing_button)
        disableAdsSwitch = view.findViewById(R.id.disable_ads_switch)
        skipVerificationSwitch = view.findViewById(R.id.skip_verification_switch)

        // Set initial states
        proVersionSwitch.isChecked = appVersionManager.isProVersion()
        disableAdsSwitch.isChecked = appVersionManager.areDevAdsDisabled()

        // Get saved verification preference
        val devPrefs = requireContext().getSharedPreferences("developer_settings", Context.MODE_PRIVATE)
        skipVerificationSwitch.isChecked = devPrefs.getBoolean("skip_purchase_verification", true)

        // Update UI based on current state
        updateTestModeStatus()

        // Set up the skip verification switch
        skipVerificationSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Save preference
            devPrefs.edit().putBoolean("skip_purchase_verification", isChecked).apply()

            // Send a broadcast to refresh purchases
            val intent = android.content.Intent("com.photostreamr.ACTION_REFRESH_PURCHASES")
            requireContext().sendBroadcast(intent)

            val message = if (isChecked)
                "Purchase verification will be skipped in test mode"
            else
                "Purchase verification will be performed in test mode"

            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        // Set up the ads switch
        disableAdsSwitch.setOnCheckedChangeListener { _, isChecked ->
            appVersionManager.setDevAdsDisabled(isChecked)
            val message = if (isChecked) "Ads disabled in development mode" else "Ads enabled in development mode"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        // Set up enable testing button
        enableTestingButton.setOnClickListener {
            appVersionManager.enableDevelopmentTesting()
            updateTestModeStatus()
            Toast.makeText(context, "Testing mode enabled", Toast.LENGTH_SHORT).show()
        }

        // Set up disable testing button
        disableTestingButton.setOnClickListener {
            appVersionManager.disableDevelopmentTesting()
            updateTestModeStatus()
            Toast.makeText(context, "Testing mode disabled", Toast.LENGTH_SHORT).show()
        }

        // Set up pro version switch
        proVersionSwitch.setOnCheckedChangeListener { _, isChecked ->
            appVersionManager.setProVersionForDevelopment(isChecked)
            updateTestModeStatus()

            val message = if (isChecked) "PRO version enabled" else "FREE version enabled"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

            // Update UI immediately
            versionStateText.text = "Current state: ${if (isChecked) "PRO" else "FREE"} VERSION"
        }

        // Set up close button
        view.findViewById<Button>(R.id.close_button)?.setOnClickListener {
            dismiss()
        }
    }

    private fun updateTestModeStatus() {
        val isTestMode = appVersionManager.isDevelopmentTestingMode()
        testModeText.text = if (isTestMode) {
            "Testing mode is ACTIVE"
        } else {
            "Testing mode is INACTIVE"
        }

        proVersionSwitch.isEnabled = isTestMode
        disableTestingButton.isEnabled = isTestMode
        // Also update the state of the enable button
        enableTestingButton.isEnabled = !isTestMode

        // Also update the ads switch
        disableAdsSwitch.isEnabled = isTestMode
        skipVerificationSwitch.isEnabled = isTestMode
    }
}