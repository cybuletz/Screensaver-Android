package com.example.screensaver.widgets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView
import com.example.screensaver.R

class ClockWidgetBinding(
    private val container: ViewGroup
) {
    private var rootView: View? = null
    private var clockView: TextClock? = null
    private var dateView: TextView? = null

    fun inflate(): View {
        if (rootView == null) {
            rootView = LayoutInflater.from(container.context)
                .inflate(R.layout.widget_clock, container, false)

            clockView = rootView?.findViewById(R.id.clockView)
            dateView = rootView?.findViewById(R.id.dateView)
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