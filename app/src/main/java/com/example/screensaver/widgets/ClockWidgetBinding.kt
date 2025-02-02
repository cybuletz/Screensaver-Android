package com.example.screensaver.widgets

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.example.screensaver.R

class ClockWidgetBinding(
    private val container: ViewGroup
) {
    private var rootView: View? = null
    private var clockView: TextClock? = null
    private var dateView: TextView? = null

    fun inflate(): View {
        if (rootView == null) {
            Log.d("ClockWidgetBinding", "Inflating clock widget")
            rootView = LayoutInflater.from(container.context)
                .inflate(R.layout.widget_clock, container, false)

            clockView = rootView?.findViewById(R.id.clockView)
            dateView = rootView?.findViewById(R.id.dateView)

            // Add the view with proper ConstraintLayout params
            val params = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )

            rootView?.layoutParams = params
            container.addView(rootView)

            Log.d("ClockWidgetBinding", "Clock widget inflated and added to container")
            Log.d("ClockWidgetBinding", "Clock view found: ${clockView != null}")
            Log.d("ClockWidgetBinding", "Date view found: ${dateView != null}")
        }
        return rootView!!
    }

    fun getClockView(): TextClock? = clockView
    fun getDateView(): TextView? = dateView
    fun getRootView(): View? = rootView

    fun cleanup() {
        rootView = null
        clockView = null
        dateView = null
    }
}