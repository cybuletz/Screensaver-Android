package com.example.screensaver.photos

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class PhotoManagerPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PhotoListFragment()
            1 -> AlbumListFragment()
            else -> throw IllegalStateException("Invalid position $position")
        }
    }
}