package com.example.screensaver

import android.animation.ObjectAnimator
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.PhotoLoadingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.screensaver.R

@AndroidEntryPoint
class PhotoDreamService : DreamService() {

    @Inject
    lateinit var photoLoadingManager: PhotoLoadingManager

    @Inject
    lateinit var photoManager: GooglePhotosManager

    private lateinit var primaryImageView: ImageView
    private lateinit var secondaryImageView: ImageView
    private lateinit var debugStatus: TextView
    private lateinit var debugPhotoInfo: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var rootLayout: FrameLayout

    private var currentImageView: ImageView? = null
    private var currentPhotoIndex = 0
    private var slideshowRunnable: Runnable? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "PhotoDreamService"
        private const val SLIDESHOW_DELAY = 10000L // 10 seconds
        private const val TRANSITION_DURATION = 1000L // 1 second transition
        private const val PRECACHE_COUNT = 5
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PhotoDreamService onCreate")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "PhotoDreamService onAttachedToWindow")
        try {
            isFullscreen = true
            isInteractive = true
            setScreenBright(true)
            setupDreamService()
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onAttachedToWindow", e)
            handleError(e)
        }
    }

    private fun startDreamSequence() {
        serviceScope.launch {
            try {
                loadingIndicator.isVisible = true
                if (photoManager.initialize() && verifySelectedAlbums()) {
                    loadPhotos()
                } else {
                    handleError(Exception("Failed to initialize photo service"))
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    private fun setupDreamService() {
        setContentView(R.layout.dream_layout)
        initializeViews()
        setupImageViews()
        startDreamSequence()
    }

    private fun initializeViews() {
        rootLayout = findViewById(R.id.dream_root_layout)
            ?: throw IllegalStateException("Root layout not found")

        debugStatus = findViewById<TextView>(R.id.debugStatus).apply {
            isVisible = true
        }

        debugPhotoInfo = findViewById<TextView>(R.id.debugPhotoInfo).apply {
            isVisible = true
        }

        loadingIndicator = findViewById<ProgressBar>(R.id.loadingIndicator).apply {
            isVisible = true
        }

        primaryImageView = findViewById<ImageView>(R.id.primaryImageView).apply {
            isVisible = true
        }

        secondaryImageView = findViewById<ImageView>(R.id.secondaryImageView).apply {
            isVisible = true
        }
    }

    private fun setupImageViews() {
        primaryImageView.scaleType = ImageView.ScaleType.CENTER_CROP
        secondaryImageView.scaleType = ImageView.ScaleType.CENTER_CROP
        secondaryImageView.alpha = 0f
        currentImageView = primaryImageView
    }

    private fun verifySelectedAlbums(): Boolean {
        val selectedAlbums = getSharedPreferences("screensaver_prefs", MODE_PRIVATE)
            .getStringSet("selected_albums", emptySet())

        updateDebugStatus("Selected albums: ${selectedAlbums?.size ?: 0}")
        return !selectedAlbums.isNullOrEmpty()
    }

    private suspend fun loadPhotos() {
        try {
            val photos = photoManager.loadPhotos()
            if (!photos.isNullOrEmpty()) {
                startSlideshow()
            } else {
                handleError(Exception("Failed to load photos"))
            }
        } catch (e: Exception) {
            handleError(e)
        }
    }

    private fun startSlideshow() {
        if (photoManager.getPhotoCount() == 0) {
            updateDebugStatus("No photos available for slideshow")
            return
        }

        updateDebugStatus("Starting slideshow with ${photoManager.getPhotoCount()} photos")
        precacheImages(5)

        slideshowRunnable = object : Runnable {
            override fun run() {
                displayNextPhoto()
                handler.postDelayed(this, SLIDESHOW_DELAY)
            }
        }
        handler.post(slideshowRunnable!!)
    }

    private fun displayNextPhoto() {
        if (photoManager.getPhotoCount() == 0) {
            updateDebugStatus("No photos to display")
            return
        }

        val nextImageView = if (currentImageView == primaryImageView) secondaryImageView else primaryImageView
        val url = photoManager.getPhotoUrl(currentPhotoIndex)

        if (url != null) {
            Glide.with(applicationContext)
                .load(url)
                .apply(RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .error(android.R.drawable.ic_dialog_alert))
                .listener(createGlideListener())
                .into(nextImageView)

            animateTransition(nextImageView)
            currentImageView = nextImageView
            currentPhotoIndex = (currentPhotoIndex + 1) % photoManager.getPhotoCount()
            precacheNextPhoto()
        } else {
            handleError(Exception("Invalid photo URL"))
        }
    }

    private fun createGlideListener() = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?,
            model: Any?,
            target: Target<Drawable>,
            isFirstResource: Boolean
        ): Boolean {
            updateDebugStatus("Failed to load image: ${e?.message}")
            return false
        }

        override fun onResourceReady(
            resource: Drawable,
            model: Any,
            target: Target<Drawable>,
            dataSource: DataSource,
            isFirstResource: Boolean
        ): Boolean {
            updateDebugStatus("Image loaded successfully")
            return false
        }
    }

    private fun animateTransition(nextImageView: ImageView) {
        ObjectAnimator.ofFloat(nextImageView, View.ALPHA, 0f, 1f).apply {
            duration = TRANSITION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        currentImageView?.let { currentView ->
            ObjectAnimator.ofFloat(currentView, View.ALPHA, 1f, 0f).apply {
                duration = TRANSITION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun precacheImages(count: Int) {
        for (i in 0 until minOf(count, photoManager.getPhotoCount())) {
            val index = (currentPhotoIndex + i) % photoManager.getPhotoCount()
            photoManager.getPhotoUrl(index)?.let { url ->
                Glide.with(this)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .preload()
            }
        }
    }

    private fun precacheNextPhoto() {
        photoManager.getPhotoUrl((currentPhotoIndex + 1) % photoManager.getPhotoCount())?.let { nextUrl ->
            Glide.with(this)
                .load(nextUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .preload()
        }
    }

    private fun updateDebugStatus(status: String) {
        Log.d(TAG, status)
        serviceScope.launch(Dispatchers.Main) {
            try {
                debugStatus.text = status
                debugStatus.isVisible = true
            } catch (e: Exception) {
                Log.e(TAG, "Error updating debug status", e)
            }
        }
    }

    private fun handleError(error: Exception) {
        val errorMessage = "Error: ${error.message}"
        Log.e(TAG, errorMessage, error)

        serviceScope.launch(Dispatchers.Main) {
            try {
                updateDebugStatus(errorMessage)
                loadingIndicator.isVisible = false
                Toast.makeText(applicationContext, errorMessage, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling error", e)
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.d(TAG, "Dream started")
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        Log.d(TAG, "Dream stopped")
        cleanup()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "Dream detached")
        cleanup()
    }

    private fun cleanup() {
        try {
            slideshowRunnable?.let {
                handler.removeCallbacks(it)
            }
            serviceScope.cancel()
            photoManager.cleanup()
            Glide.get(applicationContext).clearMemory()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}