package com.photostreamr.photos

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.screensaver.R
import com.example.screensaver.settings.PhotoSourcesPreferencesFragment
import android.os.Bundle

class PhotoManagerPagerAdapter(
    private val activity: FragmentActivity,
    private val hasPhotos: Boolean
) : FragmentStateAdapter(activity) {

    companion object {
        const val TAG_SOURCES = "f0"
        const val TAG_PHOTOS = "f1"
        const val TAG_ALBUMS = "f2"
    }

    override fun getItemCount(): Int = if (hasPhotos) 3 else 1

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> {
                val fragment = PhotoSourcesPreferencesFragment()
                // Add fragment tag programmatically
                fragment.arguments = Bundle().apply {
                    putString("fragment_tag", TAG_SOURCES)
                }
                fragment
            }
            1 -> PhotoListFragment()
            2 -> AlbumListFragment()
            else -> throw IllegalArgumentException("Invalid position $position")
        }
    }

    fun getPageTitle(position: Int): CharSequence {
        return when (position) {
            0 -> activity.getString(R.string.tab_sources)
            1 -> activity.getString(R.string.tab_photos)
            2 -> activity.getString(R.string.tab_albums)
            else -> ""
        }
    }
}