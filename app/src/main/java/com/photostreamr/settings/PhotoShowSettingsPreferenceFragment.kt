package com.photostreamr.settings

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import com.photostreamr.ui.PhotoDisplayManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.photostreamr.R
import com.photostreamr.version.AppVersionManager
import com.photostreamr.version.FeatureManager
import com.photostreamr.version.ProVersionPromptDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PhotoShowSettingsPreferenceFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    @Inject
    lateinit var featureManager: FeatureManager

    private var initialPreferences: Bundle? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.photoshow_settings_preferences, rootKey)
        initialPreferences = Bundle().apply {
            savePreferenceState(preferenceScreen, this)
        }

        setupPreferences()
        setupProFeatures()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeProState()
    }

    private fun getPreferenceIndex(parent: PreferenceGroup, preference: Preference): Int {
        for (i in 0 until parent.preferenceCount) {
            if (parent.getPreference(i) == preference) {
                return i
            }
        }
        return -1
    }

    private fun setupProFeatures() {
        val proFeatures = listOf(
            findPreference<ListPreference>("transition_effect"),
          //  findPreference<SeekBarPreference>("transition_duration")
        )

        proFeatures.forEach { originalPref ->
            originalPref?.let { pref ->
                if (!featureManager.isFeatureAvailable(FeatureManager.Feature.TRANSITION_EFFECTS)) {
                    // Create Pro badge preference
                    ProBadgePreference(requireContext()).apply {
                        key = pref.key
                        title = pref.title
                        summary = pref.summary
                        order = pref.order
                        isEnabled = false
                        layoutResource = pref.layoutResource // This helps with badge display

                        setOnPreferenceClickListener {
                            ProVersionPromptDialog.newInstance(FeatureManager.Feature.TRANSITION_EFFECTS)
                                .show(parentFragmentManager, "pro_version_prompt")
                            true
                        }

                        // Get the parent and replace the preference
                        val parent = pref.parent as PreferenceGroup
                        val index = getPreferenceIndex(parent, pref)
                        parent.removePreference(pref)
                        parent.addPreference(this)
                        if (index >= 0) {
                            order = pref.order
                        }
                    }
                }
            }
        }
    }

    private fun observeProState() {
        viewLifecycleOwner.lifecycleScope.launch {
            featureManager.getProVersionStateFlow().collectLatest { state ->
                when (state) {
                    is AppVersionManager.VersionState.Pro -> {
                        val proFeatures = listOf(
                            findPreference<ListPreference>("transition_effect")
                            // "transition_duration" -> SeekBarPreference(requireContext())
                        )

                        proFeatures.forEach { pref ->
                            pref?.apply {
                                isEnabled = true
                                onPreferenceClickListener = null
                                // The PRO badge will be automatically removed since we're replacing
                                // the entire preference
                            }
                        }
                    }
                    else -> { /* Free version state handled in setupProFeatures */ }
                }
            }
        }
    }

    private fun setupPreferences() {
        // Random order preference
        findPreference<SwitchPreferenceCompat>("random_order")?.setOnPreferenceChangeListener { _, newValue ->
            val isRandomOrder = newValue as Boolean
            photoDisplayManager.updateSettings(isRandomOrder = isRandomOrder)
            true
        }

        // Transition duration
        findPreference<SeekBarPreference>("transition_duration")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val duration = (newValue as Int)
                photoDisplayManager.updateSettings(transitionDuration = duration * 1000L)
                summary = "$duration seconds for transition animation"
                true
            }

            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt("transition_duration", 2)
            summary = "$currentValue seconds for transition animation"
        }

        // Photo interval
        findPreference<SeekBarPreference>("photo_interval")?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val interval = (newValue as Int)
                summary = "Display each photo for $interval seconds"
                photoDisplayManager.apply {
                    stopPhotoDisplay()
                    startPhotoDisplay()
                }
                true
            }

            val currentValue = PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getInt(PhotoDisplayManager.PREF_KEY_INTERVAL, PhotoDisplayManager.DEFAULT_INTERVAL_SECONDS)
            summary = "Display each photo for $currentValue seconds"
        }

        // Photo scale preference - store in SharedPreferences only
        findPreference<ListPreference>("photo_scale")?.setOnPreferenceChangeListener { _, _ ->
            photoDisplayManager.apply {
                stopPhotoDisplay()
                startPhotoDisplay()
            }
            true
        }

        // Transition effect - store in SharedPreferences only
        findPreference<ListPreference>("transition_effect")?.setOnPreferenceChangeListener { _, _ ->
            photoDisplayManager.apply {
                stopPhotoDisplay()
                startPhotoDisplay()
            }
            true
        }
    }

    private fun savePreferenceState(screen: PreferenceScreen, outState: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            preference.sharedPreferences?.let { prefs ->
                when (preference.key) {
                    "random_order" -> outState.putBoolean(preference.key, prefs.getBoolean(preference.key, true))
                    "photo_scale" -> outState.putString(preference.key, prefs.getString(preference.key, "fill"))
                    "transition_effect" -> outState.putString(preference.key, prefs.getString(preference.key, "fade"))
                    "transition_duration" -> outState.putInt(preference.key, prefs.getInt(preference.key, 2))
                    "photo_interval" -> outState.putInt(preference.key, prefs.getInt(preference.key, 5))
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
                        "random_order" -> putBoolean(key, initial.getBoolean(key))
                        "photo_scale" -> putString(key, initial.getString(key))
                        "transition_effect" -> putString(key, initial.getString(key))
                        "transition_duration" -> putInt(key, initial.getInt(key))
                        "photo_interval" -> putInt(key, initial.getInt(key))
                    }
                    apply()
                }
            }
        }
        // Reset PhotoDisplayManager with original settings
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings()
            startPhotoDisplay()
        }
    }

    fun applyChanges() {
        // Force restart photo display to ensure all changes are applied
        photoDisplayManager.apply {
            stopPhotoDisplay()
            updateSettings()
            startPhotoDisplay()
        }
    }
}