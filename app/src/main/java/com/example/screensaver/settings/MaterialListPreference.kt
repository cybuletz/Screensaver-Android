package com.example.screensaver.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.example.screensaver.R

class MaterialListPreference : ListPreference {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        layoutResource = R.layout.settings_preference_card
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val chevronView = holder.findViewById(R.id.preference_chevron) as? ImageView
        val switchView = holder.findViewById(R.id.preference_switch) as? View

        chevronView?.apply {
            visibility = View.VISIBLE
            alpha = if (isEnabled) 1f else 0.5f
        }

        switchView?.visibility = View.GONE
    }
}