package com.example.screensaver.binding

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.screensaver.R
import com.example.screensaver.model.Album
import com.example.screensaver.model.MediaItem
import com.example.screensaver.utils.PhotoLoadingManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom data binding adapters for the application's views
 */
object PhotoBindingAdapters {

    private const val CROSSFADE_DURATION = 300
    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    /**
     * Loads and displays a photo in an ImageView
     */
    @JvmStatic
    @BindingAdapter(
        value = ["photoUrl", "placeholder", "errorPlaceholder", "quality", "onLoadingComplete"],
        requireAll = false
    )
    fun loadPhoto(
        view: ImageView,
        photoUrl: String?,
        placeholder: Drawable?,
        errorPlaceholder: Drawable?,
        quality: Int?,
        onLoadingComplete: ((Boolean) -> Unit)?
    ) {
        if (photoUrl == null) {
            view.setImageDrawable(errorPlaceholder)
            onLoadingComplete?.invoke(false)
            return
        }

        val requestBuilder = Glide.with(view.context)
            .load(photoUrl)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(placeholder)
            .error(errorPlaceholder ?: R.drawable.ic_error_placeholder)

        // Apply quality settings
        quality?.let { q ->
            when (q) {
                PhotoLoadingManager.QUALITY_LOW -> requestBuilder.override(720, 720)
                PhotoLoadingManager.QUALITY_MEDIUM -> requestBuilder.override(1080, 1080)
                PhotoLoadingManager.QUALITY_HIGH -> requestBuilder.override(Target.SIZE_ORIGINAL)
            }
        }

        requestBuilder.listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>?,
                isFirstResource: Boolean
            ): Boolean {
                onLoadingComplete?.invoke(false)
                return false
            }

            override fun onResourceReady(
                resource: Drawable?,
                model: Any?,
                target: Target<Drawable>?,
                dataSource: DataSource?,
                isFirstResource: Boolean
            ): Boolean {
                onLoadingComplete?.invoke(true)
                return false
            }
        }).into(view)
    }

    /**
     * Loads and displays an album thumbnail
     */
    @JvmStatic
    @BindingAdapter("albumThumbnail")
    fun loadAlbumThumbnail(view: ImageView, album: Album?) {
        album?.coverPhotoUrl?.let { url ->
            Glide.with(view.context)
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_album_placeholder)
                .error(R.drawable.ic_error_placeholder)
                .override(300, 300)
                .into(view)
        } ?: view.setImageResource(R.drawable.ic_album_placeholder)
    }

    /**
     * Sets view visibility based on a boolean
     */
    @JvmStatic
    @BindingAdapter("isVisible")
    fun setVisibility(view: View, isVisible: Boolean) {
        view.isVisible = isVisible
    }

    /**
     * Displays formatted date
     */
    @JvmStatic
    @BindingAdapter("date")
    fun setDate(view: TextView, date: Date?) {
        view.text = date?.let { dateFormat.format(it) } ?: ""
    }

    /**
     * Displays formatted time
     */
    @JvmStatic
    @BindingAdapter("time")
    fun setTime(view: TextView, date: Date?) {
        view.text = date?.let { timeFormat.format(it) } ?: ""
    }

    /**
     * Displays photo location
     */
    @JvmStatic
    @BindingAdapter("photoLocation")
    fun setPhotoLocation(view: TextView, mediaItem: MediaItem?) {
        view.text = mediaItem?.location ?: ""
        view.isVisible = !mediaItem?.location.isNullOrEmpty()
    }

    /**
     * Sets alpha based on transition progress
     */
    @JvmStatic
    @BindingAdapter("transitionAlpha")
    fun setTransitionAlpha(view: View, progress: Float) {
        view.alpha = progress
    }

    /**
     * Displays album photo count
     */
    @JvmStatic
    @BindingAdapter("photoCount")
    fun setPhotoCount(view: TextView, count: Int) {
        view.text = view.context.resources.getQuantityString(
            R.plurals.photo_count,
            count,
            count
        )
    }

    /**
     * Sets selected state with animation
     */
    @JvmStatic
    @BindingAdapter("selectedWithAnim")
    fun setSelectedWithAnimation(view: View, selected: Boolean) {
        if (view.isSelected != selected) {
            view.animate()
                .scaleX(if (selected) 1.1f else 1.0f)
                .scaleY(if (selected) 1.1f else 1.0f)
                .alpha(if (selected) 1.0f else 0.7f)
                .setDuration(200)
                .start()
            view.isSelected = selected
        }
    }

    /**
     * Displays error state
     */
    @JvmStatic
    @BindingAdapter("showError")
    fun showError(view: View, hasError: Boolean) {
        if (hasError) {
            view.setBackgroundResource(R.drawable.bg_error)
            view.animate()
                .alpha(0.5f)
                .setDuration(200)
                .start()
        } else {
            view.background = null
            view.animate()
                .alpha(1.0f)
                .setDuration(200)
                .start()
        }
    }
}