package com.example.screensaver.widgets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R

class WidgetPreferenceDialog : DialogFragment() {
    private lateinit var widgetType: WidgetType

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
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_widget_preferences, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
        }

        childFragmentManager
            .beginTransaction()
            .replace(R.id.widget_preferences_container, fragment)
            .commit()
    }
}