package com.example.screensaver.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DisplaySettingsDialog : DialogFragment() {
    private var displaySettingsFragment: DisplaySettingsPreferenceFragment? = null

    companion object {
        fun newInstance() = DisplaySettingsDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.PhotoSourcesDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_display_settings, container, false)

        // Set width to wrap content with minimum width
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displaySettingsFragment = DisplaySettingsPreferenceFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.display_settings_container, displaySettingsFragment!!)
            .commit()

        view.findViewById<Button>(R.id.cancel_button).setOnClickListener {
            displaySettingsFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<Button>(R.id.ok_button).setOnClickListener {
            displaySettingsFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        displaySettingsFragment = null
        super.onDestroyView()
    }
}