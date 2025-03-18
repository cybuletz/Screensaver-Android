package com.example.screensaver

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.example.screensaver.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PhotoViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPhotoDisplay()
        observeViewModel()
    }

    private fun setupPhotoDisplay() {
        lifecycleScope.launch {
            if (photoManager.initialize()) {
                val photos = photoManager.loadPhotos()
                if (!photos.isNullOrEmpty()) {
                    binding.noPhotosMessage.isVisible = false
                    binding.photoPreview.isVisible = true
                    startPhotoDisplay()
                } else {
                    showNoPhotosMessage()
                }
            } else {
                showError("Failed to initialize photo display")
            }
        }
    }

    private fun startPhotoDisplay() {
        viewModel.startPhotoChanging()
    }

    private fun observeViewModel() {
        viewModel.currentPhoto.observe(viewLifecycleOwner) { photoUrl ->
            photoUrl?.let { url ->
                Glide.with(this)
                    .load(url)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(binding.photoPreview)
            }
        }
    }

    private fun showNoPhotosMessage() {
        binding.apply {
            photoPreview.isVisible = false
            noPhotosMessage.isVisible = true
            noPhotosMessage.text = getString(R.string.no_photos_selected)
        }
    }

    private fun showError(message: String) {
        // Add error handling implementation
        binding.apply {
            photoPreview.isVisible = false
            noPhotosMessage.isVisible = true
            noPhotosMessage.text = message
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}