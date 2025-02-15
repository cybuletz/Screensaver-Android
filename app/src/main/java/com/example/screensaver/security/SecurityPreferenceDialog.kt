package com.example.screensaver.security

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceFragmentCompat
import com.example.screensaver.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint
import androidx.preference.SwitchPreferenceCompat
import com.example.screensaver.data.SecureStorage
import javax.inject.Inject

@AndroidEntryPoint
class SecurityPreferenceDialog : DialogFragment() {

    companion object {
        fun newInstance(): SecurityPreferenceDialog = SecurityPreferenceDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_widget_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.dialog_toolbar)
        toolbar.title = getString(R.string.security_preferences_title)

        val fragment = SecurityPreferenceFragment()

        childFragmentManager
            .beginTransaction()
            .replace(R.id.widget_preferences_container, fragment)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            dismiss()
        }
    }
}

@AndroidEntryPoint
class SecurityPreferenceFragment : PreferenceFragmentCompat() {
    @Inject
    lateinit var secureStorage: SecureStorage

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.security_preferences, rootKey)

        // Handle remove security on restart preference
        findPreference<SwitchPreferenceCompat>("remove_security_on_restart")?.apply {
            isChecked = secureStorage.shouldRemoveSecurityOnRestart()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                secureStorage.setRemoveSecurityOnRestart(enabled)
                true
            }
        }

        // Handle remove security on minimize preference
        findPreference<SwitchPreferenceCompat>("remove_security_on_minimize")?.apply {
            isChecked = secureStorage.shouldRemoveSecurityOnMinimize()
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                secureStorage.setRemoveSecurityOnMinimize(enabled)
                true
            }
        }
    }
}