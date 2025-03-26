package com.photostreamr.widgets

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

    companion object {
        private const val TAG = "ClockWidgetBinding"
    }

    fun inflate(): View {
        try {
            Log.d(TAG, "Starting inflate() for ClockWidgetBinding")
            if (rootView == null) {
                rootView = LayoutInflater.from(container.context)
                    .inflate(R.layout.widget_clock, container, false)
                Log.d(TAG, "Layout inflated")

                clockView = rootView?.findViewById(R.id.clockView)
                dateView = rootView?.findViewById(R.id.dateView)
                Log.d(TAG, "Views found - Clock: ${clockView != null}, Date: ${dateView != null}")

                // Configure TextClock
                clockView?.apply {
                    format12Hour = "hh:mm a"
                    format24Hour = "HH:mm"
                    Log.d(TAG, "Clock formats set")
                }

                // Add the view to container
                val params = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // Default to TOP_START
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    setMargins(32, 32, 32, 32)
                }

                rootView?.layoutParams = params
                container.addView(rootView)
                Log.d(TAG, "Root view added to container with params")
            } else {
                Log.d(TAG, "Root view already exists")
            }
            return rootView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error in inflate()", e)
            throw e
        }
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