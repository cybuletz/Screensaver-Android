package com.example.screensaver

@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var photoManager: GooglePhotosManager

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}