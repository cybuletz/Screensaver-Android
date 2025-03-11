package com.example.screensaver.photos

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.screensaver.R
import com.example.screensaver.settings.PhotoSourcesPreferencesFragment

class PhotoManagerPagerAdapter(
    private val activity: FragmentActivity,
    private val hasPhotos: Boolean
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = if (hasPhotos) 3 else 1

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PhotoSourcesPreferencesFragment()
            1 -> PhotoListFragment()
            2 -> AlbumListFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }

    fun getPageTitle(position: Int): String {
        return when (position) {
            0 -> activity.getString(R.string.sources)
            1 -> activity.getString(R.string.photos)
            2 -> activity.getString(R.string.albums)
            else -> ""
        }
    }
}