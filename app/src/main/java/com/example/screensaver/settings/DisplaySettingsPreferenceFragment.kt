package com.example.screensaver.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import com.example.screensaver.R
import com.example.screensaver.utils.BrightnessManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.utils.ScreenOrientation

@AndroidEntryPoint
class DisplaySettingsPreferenceFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var brightnessManager: BrightnessManager

    private var initialPreferences: Bundle? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.display_preferences, rootKey)
        initialPreferences = Bundle().apply {
            savePreferenceState(preferenceScreen, this)
        }

        findPreference<SwitchPreferenceCompat>("keep_screen_on")?.setOnPreferenceChangeListener { _, newValue ->
            val keepScreenOn = newValue as Boolean
            if (keepScreenOn) {
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            true
        }

        findPreference<ListPreference>("screen_orientation")?.setOnPreferenceChangeListener { _, newValue ->
            val orientation = ScreenOrientation.valueOf(newValue.toString())
            requireActivity().requestedOrientation = orientation.androidValue
            true
        }

        // Add click listener for brightness settings
        findPreference<Preference>("brightness_settings")?.setOnPreferenceClickListener {
            showBrightnessDialog()
            true
        }
    }

    private fun showBrightnessDialog() {
        if (!checkWriteSettingsPermission()) {
            Toast.makeText(requireContext(), "Permission needed to control brightness", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Screen Brightness")
            .setView(R.layout.dialog_brightness_settings)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()

        dialog.show()

        // Get references to views
        val brightnessSwitch = dialog.findViewById<SwitchMaterial>(R.id.custom_brightness)!!
        val brightnessSlider = dialog.findViewById<Slider>(R.id.brightness_slider)!!
        val brightnessValue = dialog.findViewById<TextView>(R.id.brightness_value)!!

        // Set initial states
        brightnessSwitch.isChecked = brightnessManager.isCustomBrightnessEnabled()
        brightnessSlider.value = brightnessManager.getCurrentBrightness().toFloat()
        brightnessSlider.isEnabled = brightnessSwitch.isChecked
        brightnessValue.text = "${brightnessManager.getCurrentBrightness()}%"

        // Handle switch changes
        brightnessSwitch.setOnCheckedChangeListener { _, isChecked ->
            brightnessSlider.isEnabled = isChecked
            if (isChecked) {
                brightnessManager.setBrightness(requireActivity().window, brightnessSlider.value.toInt())
            } else {
                brightnessManager.resetBrightness(requireActivity().window)
            }
            updateBrightnessSummary()
        }

        // Handle slider changes
        brightnessSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val brightness = value.toInt()
                brightnessValue.text = "$brightness%"
                if (brightnessSwitch.isChecked) {
                    brightnessManager.setBrightness(requireActivity().window, brightness)
                }
                updateBrightnessSummary()
            }
        }
    }

    private fun checkWriteSettingsPermission(): Boolean {
        return requireContext().let { context ->
            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:" + context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                false
            } else {
                true
            }
        }
    }

    private fun updateBrightnessSummary() {
        findPreference<Preference>("brightness_settings")?.summary = if (brightnessManager.isCustomBrightnessEnabled()) {
            "Custom: ${brightnessManager.getCurrentBrightness()}%"
        } else {
            "Using system brightness"
        }
    }

    private fun savePreferenceState(screen: PreferenceScreen, outState: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            preference.sharedPreferences?.let { prefs ->
                when (preference.key) {
                    "keep_screen_on" -> outState.putBoolean(preference.key, prefs.getBoolean(preference.key, false))
                    "screen_orientation" -> outState.putString(preference.key, prefs.getString(preference.key, "SYSTEM"))
                    "brightness_settings" -> outState.putInt(preference.key, prefs.getInt(preference.key, 50))
                }
            }
        }
    }

    fun cancelChanges() {
        val prefs = preferenceManager.sharedPreferences
        initialPreferences?.let { initial ->
            initial.keySet().forEach { key ->
                prefs?.edit()?.apply {
                    when (key) {
                        "keep_screen_on" -> putBoolean(key, initial.getBoolean(key))
                        "screen_orientation" -> putString(key, initial.getString(key))
                        "brightness_settings" -> putInt(key, initial.getInt(key))
                    }
                    apply()
                }
            }
        }
    }

    fun applyChanges() {
        val prefs = preferenceManager.sharedPreferences
        // Apply keep screen on setting
        if (prefs?.getBoolean("keep_screen_on", false) == true) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}