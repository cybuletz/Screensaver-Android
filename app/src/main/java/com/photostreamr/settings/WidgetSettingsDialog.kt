package com.photostreamr.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.photostreamr.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class WidgetsSettingsDialog : DialogFragment() {
    private var widgetsSettingsFragment: WidgetsSettingsPreferenceFragment? = null

    companion object {
        fun newInstance() = WidgetsSettingsDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_widgets_settings, container, false)

        // Add transparent background
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // Set layout size
            setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set dialog title
        view.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.widgets_settings_title)

        widgetsSettingsFragment = WidgetsSettingsPreferenceFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.widgets_settings_container, widgetsSettingsFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            widgetsSettingsFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            widgetsSettingsFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        widgetsSettingsFragment = null
        super.onDestroyView()
    }
}