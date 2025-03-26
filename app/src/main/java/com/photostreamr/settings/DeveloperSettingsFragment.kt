package com.photostreamr.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.photostreamr.R
import com.photostreamr.version.AppVersionManager
import com.photostreamr.version.FeatureManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DeveloperSettingsFragment : Fragment() {

    @Inject
    lateinit var appVersionManager: AppVersionManager

    @Inject
    lateinit var featureManager: FeatureManager

    private lateinit var proVersionSwitch: Switch
    private lateinit var testModeText: TextView
    private lateinit var versionStateText: TextView
    private lateinit var disableTestingButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_developer_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        proVersionSwitch = view.findViewById(R.id.pro_version_switch)
        testModeText = view.findViewById(R.id.test_mode_status)
        versionStateText = view.findViewById(R.id.version_state)
        disableTestingButton = view.findViewById(R.id.disable_testing_button)

        // Set initial switch state
        proVersionSwitch.isChecked = appVersionManager.isProVersion()
        updateTestModeStatus()

        // Set up switch listener
        proVersionSwitch.setOnCheckedChangeListener { _, isChecked ->
            appVersionManager.setProVersionForDevelopment(isChecked)
            updateTestModeStatus()
        }

        // Set up disable testing button
        disableTestingButton.setOnClickListener {
            appVersionManager.disableDevelopmentTesting()
            updateTestModeStatus()
        }

        // Observe version state changes
        lifecycleScope.launch {
            featureManager.getProVersionStateFlow().collectLatest { state ->
                when (state) {
                    is AppVersionManager.VersionState.Pro -> {
                        versionStateText.text = "Current state: PRO VERSION"
                    }
                    is AppVersionManager.VersionState.Free -> {
                        versionStateText.text = "Current state: FREE VERSION"
                    }
                }
            }
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
    }
}