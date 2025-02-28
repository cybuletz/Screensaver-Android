package com.example.screensaver.photos

import android.content.ContentValues.TAG
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.RequestManager
import com.example.screensaver.databinding.FragmentAlbumListBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AlbumListFragment : Fragment() {
    private var _binding: FragmentAlbumListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PhotoManagerViewModel by activityViewModels()

    @Inject
    lateinit var glide: RequestManager

    private lateinit var virtualAlbumsAdapter: VirtualAlbumsAdapter

    companion object {
        private const val TAG = "AlbumListFragment"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlbumListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupVirtualAlbumsRecyclerView()
    }

    private fun setupVirtualAlbumsRecyclerView() {
        virtualAlbumsAdapter = VirtualAlbumsAdapter(
            glide = glide,
            onAlbumClick = { album ->
                Log.d(TAG, "Album clicked: ${album.id}")
            },
            onAlbumOptionsClick = { album ->
                (activity as? PhotoManagerActivity)?.showVirtualAlbumOptions(album)
            }
        )

        binding.recyclerViewVirtualAlbums.apply {
            adapter = virtualAlbumsAdapter
            layoutManager = GridLayoutManager(requireContext(), 2)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            Log.d(TAG, "Starting to observe virtual albums")
            viewModel.virtualAlbums.collectLatest { albums ->
                Log.d(TAG, "Received ${albums.size} albums")
                virtualAlbumsAdapter.submitList(albums)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}