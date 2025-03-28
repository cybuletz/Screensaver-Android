package com.photostreamr.ui

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.photostreamr.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PhotoDisplaySettingsFragment : PreferenceFragmentCompat() {

    @Inject
    lateinit var photoDisplayManager: PhotoDisplayManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.photo_display_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // Update UI with current settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        photoDisplayManager.updateSettings(
            transitionDuration = prefs.getInt("transition_duration", 1000).toLong(),
            showLocation = prefs.getBoolean("show_location", false),
            isRandomOrder = prefs.getBoolean("random_order", false)
        )
    }
}