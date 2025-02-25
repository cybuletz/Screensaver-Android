package com.example.screensaver.photomanager

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.screensaver.R
import com.example.screensaver.databinding.FragmentPhotoManagementBinding
import com.example.screensaver.photomanager.transitions.SharedAxisTransitionSet
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PhotoManagementFragment : Fragment() {
    private var _binding: FragmentPhotoManagementBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoManagementViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = SharedAxisTransitionSet(requireContext(), true)
        returnTransition = SharedAxisTransitionSet(requireContext(), false)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViewPager()
        observeViewModel()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = PhotoManagementPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.photos)
                1 -> getString(R.string.collections)
                else -> null
            }
        }.attach()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { state ->
                binding.loadingIndicator.visibility = if (state.isLoading) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class PhotoManagementPagerAdapter(fragment: Fragment) :
        FragmentStateAdapter(fragment) {

        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> PhotoSelectionFragment()
                1 -> CollectionListFragment()
                else -> throw IllegalArgumentException("Invalid position $position")
            }
        }
    }
}