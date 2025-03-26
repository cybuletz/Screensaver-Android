package com.photostreamr.widgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.preference.*
import com.google.android.material.button.MaterialButton
import javax.inject.Inject
import android.widget.TextView
import androidx.preference.PreferenceFragmentCompat

class WidgetPreferenceDialog : DialogFragment() {

    @Inject
    lateinit var widgetManager: WidgetManager

    private lateinit var widgetType: WidgetType
    private var initialPreferences: Bundle? = null

    companion object {
        private const val ARG_WIDGET_TYPE = "widget_type"

        fun newInstance(widgetType: WidgetType): WidgetPreferenceDialog {
            return WidgetPreferenceDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_WIDGET_TYPE, widgetType.name)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetType = WidgetType.valueOf(requireArguments().getString(ARG_WIDGET_TYPE)!!)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog) // Updated style
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Add transparent background from MusicSourcesDialog
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return inflater.inflate(R.layout.dialog_widget_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Update title text using TextView instead of MaterialToolbar
        view.findViewById<TextView>(R.id.dialog_title).text = when (widgetType) {
            WidgetType.CLOCK -> getString(R.string.clock_widget_preferences)
            WidgetType.WEATHER -> getString(R.string.weather_widget_preferences)
            WidgetType.MUSIC -> getString(R.string.music_widget_preferences)
        }

        val fragment = when (widgetType) {
            WidgetType.CLOCK -> WidgetPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString("widget_type", WidgetType.CLOCK.name)
                }
            }
            WidgetType.WEATHER -> WidgetPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString("widget_type", WidgetType.WEATHER.name)
                }
            }
            WidgetType.MUSIC -> WidgetPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString("widget_type", WidgetType.MUSIC.name)
                }
            }
        }

        childFragmentManager
            .beginTransaction()
            .replace(R.id.widget_preferences_container, fragment)
            .commit()

        // Store initial preferences state after fragment is added
        childFragmentManager.executePendingTransactions()
        (fragment as? PreferenceFragmentCompat)?.preferenceScreen?.let { screen ->
            initialPreferences = Bundle().apply {
                savePreferenceState(screen, this)
            }
        }

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            // Restore initial preferences state
            initialPreferences?.let { initial ->
                (childFragmentManager.findFragmentById(R.id.widget_preferences_container) as? PreferenceFragmentCompat)?.let { prefFragment ->
                    prefFragment.preferenceScreen?.let { screen ->
                        restorePreferenceState(screen, initial)
                    }
                }
            }
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            // Current state is already saved as preferences are updated in real-time
            dismiss()
        }
    }

    private fun savePreferenceState(screen: PreferenceScreen, outState: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            when (preference) {
                is EditTextPreference -> {
                    outState.putString(preference.key, preference.text)
                }
                is SwitchPreferenceCompat -> {
                    outState.putBoolean(preference.key, preference.isChecked)
                }
                is ListPreference -> {
                    outState.putString(preference.key, preference.value)
                }
                else -> {
                    // Handle other preference types if needed
                }
            }
        }
    }

    private fun restorePreferenceState(screen: PreferenceScreen, state: Bundle) {
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            when (preference) {
                is EditTextPreference -> {
                    state.getString(preference.key)?.let { value ->
                        preference.text = value
                    }
                }
                is SwitchPreferenceCompat -> {
                    state.getBoolean(preference.key, preference.isChecked).also { value ->
                        preference.isChecked = value
                    }
                }
                is ListPreference -> {
                    state.getString(preference.key)?.let { value ->
                        preference.value = value
                    }
                }
                else -> {
                    // Handle other preference types if needed
                }
            }
        }
    }
}