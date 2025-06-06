package com.photostreamr.widgets

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.photostreamr.R

class WeatherWidgetBinding(
    private val container: ViewGroup
) {
    private var rootView: View? = null
    private var weatherIcon: ImageView? = null
    private var temperatureView: TextView? = null

    companion object {
        private const val TAG = "WeatherWidgetBinding"
    }

    fun inflate(): View {
        try {
            Log.d(TAG, "Starting inflate() for WeatherWidgetBinding")

            // Always clean up existing view first to avoid duplicates
            cleanup()

            // Inflate the layout but DON'T attach it to the container yet
            rootView = LayoutInflater.from(container.context)
                .inflate(R.layout.widget_weather, container, false)
            Log.d(TAG, "Layout inflated")

            weatherIcon = rootView?.findViewById(R.id.weatherIcon)
            temperatureView = rootView?.findViewById(R.id.temperatureView)
            Log.d(TAG, "Views found - Icon: ${weatherIcon != null}, Temperature: ${temperatureView != null}")

            // Configure initial view parameters without adding to container
            val params = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rootView?.layoutParams = params

            // Set initial visibility to GONE
            rootView?.visibility = View.GONE

            return rootView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error in inflate()", e)
            throw e
        }
    }

    fun getWeatherIcon(): ImageView? = weatherIcon
    fun getTemperatureView(): TextView? = temperatureView
    fun getRootView(): View? = rootView

    fun cleanup() {
        // Remove view from parent if it exists
        rootView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        rootView = null
        weatherIcon = null
        temperatureView = null
    }
}