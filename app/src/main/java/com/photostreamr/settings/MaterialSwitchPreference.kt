package com.photostreamr.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import androidx.preference.SwitchPreference
import androidx.preference.PreferenceViewHolder
import com.example.screensaver.R
import com.google.android.material.switchmaterial.SwitchMaterial

class MaterialSwitchPreference : SwitchPreference {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    init {
        layoutResource = R.layout.settings_preference_card
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val chevronView = holder.findViewById(R.id.preference_chevron) as? ImageView
        val switchView = holder.findViewById(R.id.preference_switch) as? SwitchMaterial

        chevronView?.visibility = View.GONE

        switchView?.apply {
            visibility = View.VISIBLE
            isChecked = getPersistedBoolean(false)
            alpha = if (isEnabled) 1f else 0.5f
        }
    }
}