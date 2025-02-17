package com.example.screensaver.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SecurityPreferenceDialog : DialogFragment() {
    companion object {
        fun newInstance() = SecurityPreferenceDialog()
    }
    private var securityFragment: SecurityPreferenceFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_security_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.dialog_toolbar).apply {
            title = getString(R.string.security_preferences_title)
        }

        securityFragment = SecurityPreferenceFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.security_preferences_container, securityFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            securityFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            securityFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        securityFragment = null
        super.onDestroyView()
    }
}