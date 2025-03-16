package com.example.screensaver.music

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

class MusicSourcesDialog : DialogFragment() {

    private var musicPreferenceFragment: MusicPreferenceFragment? = null

    companion object {
        fun newInstance(): MusicSourcesDialog {
            return MusicSourcesDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Add this block to set transparent window background
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return inflater.inflate(R.layout.dialog_music_sources, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicPreferenceFragment = MusicPreferenceFragment().apply {
            setOnPreferenceChangeCallback {
                // Force dialog size recalculation when preferences change
                dialog?.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
        }

        childFragmentManager
            .beginTransaction()
            .replace(R.id.music_preferences_container, musicPreferenceFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            musicPreferenceFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            musicPreferenceFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            // Set the background drawable first
            setBackgroundDrawableResource(android.R.color.transparent)

            // Get screen width
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels

            // Calculate dialog width (e.g., 90% of screen width)
            val dialogWidth = (screenWidth * 0.95).toInt()  // You can adjust 0.90 to your preferred ratio

            // Set the layout params with the calculated width
            setLayout(
                dialogWidth,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onDestroyView() {
        musicPreferenceFragment = null
        super.onDestroyView()
    }
}