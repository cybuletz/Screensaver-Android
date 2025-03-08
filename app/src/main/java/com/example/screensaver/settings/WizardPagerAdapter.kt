package com.example.screensaver.settings

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class WizardPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = TOTAL_PAGES

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SourceSelectionFragment()
            1 -> PhotoSelectionWizardFragment()
            2 -> QuickAlbumFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }

    companion object {
        const val TOTAL_PAGES = 3
    }
}
