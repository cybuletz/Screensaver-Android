package com.photostreamr.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SecurityPreferenceDialog : DialogFragment() {
    private var securityPreferenceFragment: SecurityPreferenceFragment? = null

    companion object {
        fun newInstance() = SecurityPreferenceDialog()
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
        return inflater.inflate(R.layout.dialog_security_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        securityPreferenceFragment = SecurityPreferenceFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.security_preferences_container, securityPreferenceFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            securityPreferenceFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            securityPreferenceFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        securityPreferenceFragment = null
        super.onDestroyView()
    }
}