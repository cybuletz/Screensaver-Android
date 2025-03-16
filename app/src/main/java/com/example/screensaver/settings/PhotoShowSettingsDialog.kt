package com.example.screensaver.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PhotoShowSettingsDialog : DialogFragment() {
    private var commonSettingsFragment: PhotoShowSettingsPreferenceFragment? = null

    companion object {
        fun newInstance() = PhotoShowSettingsDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog) // Style change from MusicSourcesDialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Add transparent background from MusicSourcesDialog
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // Keep original size management
            setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return inflater.inflate(R.layout.dialog_photoshow_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        commonSettingsFragment = PhotoShowSettingsPreferenceFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.common_settings_container, commonSettingsFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            commonSettingsFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            commonSettingsFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        commonSettingsFragment = null
        super.onDestroyView()
    }
}