package com.photostreamr.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.photostreamr.R

class ProBadgePreference : Preference {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        widgetLayoutResource = R.layout.preference_widget_frame // Make sure widget frame is used
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as? ViewGroup
        if (widgetFrame != null) {
            // Ensure widget frame is visible
            widgetFrame.visibility = View.VISIBLE

            // Remove any existing badge
            widgetFrame.findViewById<View>(R.id.pro_badge)?.let {
                widgetFrame.removeView(it)
            }

            // Add new badge
            TextView(context).apply {
                id = R.id.pro_badge
                text = "PRO"
                setTextColor(Color.parseColor("#673AB7"))
                textSize = 12f
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setPadding(24, 8, 24, 8)

                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1A673AB7"))
                    setStroke(2, Color.parseColor("#673AB7"))
                    cornerRadius = 8f
                }

                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 16
                }

                widgetFrame.addView(this)
            }
        }
    }
}