package com.example.screensaver.settings

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.example.screensaver.R
import com.google.android.material.card.MaterialCardView

abstract class MaterialPreferenceBase : Preference {
    protected var robotoRegular: Typeface? = null
    protected var robotoBold: Typeface? = null

    constructor(context: Context) : super(context) {
        initFonts(context)
    }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initFonts(context)
        init()
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initFonts(context)
        init()
    }

    private fun initFonts(context: Context) {
        robotoRegular = ResourcesCompat.getFont(context, R.font.roboto_regular)
        robotoBold = ResourcesCompat.getFont(context, R.font.roboto_bold)
    }

    private fun init() {
        layoutResource = R.layout.settings_preference_card
    }

    protected fun setupBasicViews(holder: PreferenceViewHolder) {
        val cardView = holder.itemView as? MaterialCardView
        val titleView = holder.findViewById(R.id.preference_title) as? TextView
        val summaryView = holder.findViewById(R.id.preference_summary) as? TextView
        val iconView = holder.findViewById(R.id.preference_icon) as? ImageView

        cardView?.apply {
            radius = context.resources.getDimension(R.dimen.settings_card_corner_radius)
            elevation = context.resources.getDimension(R.dimen.settings_card_elevation)
            strokeWidth = if (isEnabled) 0 else 1
        }

        titleView?.apply {
            text = title
            alpha = if (isEnabled) 1f else 0.5f
            robotoBold?.let { typeface = it }
        }

        summaryView?.apply {
            text = summary
            alpha = if (isEnabled) 0.7f else 0.3f
            visibility = if (summary.isNullOrEmpty()) View.GONE else View.VISIBLE
            robotoRegular?.let { typeface = it }
        }

        iconView?.apply {
            icon?.let {
                setImageDrawable(it)
                visibility = View.VISIBLE
                alpha = if (isEnabled) 1f else 0.5f
            } ?: run {
                visibility = View.GONE
            }
        }
    }
}