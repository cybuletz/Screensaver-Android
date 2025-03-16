package com.example.screensaver.settings

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.example.screensaver.R
import com.google.android.material.card.MaterialCardView

class MaterialBasicPreference : Preference {
    constructor(context: Context) : super(context) {
        init(context)
    }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context)
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {
        layoutResource = R.layout.settings_preference_card
        isSelectable = true
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val titleView = holder.findViewById(R.id.preference_title) as? TextView
        val summaryView = holder.findViewById(R.id.preference_summary) as? TextView
        val iconView = holder.findViewById(R.id.preference_icon) as? ImageView
        val chevronView = holder.findViewById(R.id.preference_chevron) as? ImageView
        val switchView = holder.findViewById(R.id.preference_switch) as? View

        titleView?.apply {
            text = title
            visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        summaryView?.apply {
            text = summary
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        iconView?.apply {
            icon?.let {
                setImageDrawable(it)
                visibility = View.VISIBLE
            } ?: run {
                visibility = View.GONE
            }
        }

        chevronView?.visibility = View.VISIBLE
        switchView?.visibility = View.GONE

        holder.itemView.apply {
            isClickable = true
            isFocusable = true

            setOnClickListener {
                Log.d("MaterialBasicPreference", "Preference clicked: $key")
                // Simply call callChangeListener which will trigger onPreferenceTreeClick
                callChangeListener(this@MaterialBasicPreference)
            }
        }
    }
}