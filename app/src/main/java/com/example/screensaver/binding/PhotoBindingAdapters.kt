package com.example.screensaver.binding

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.databinding.BindingAdapter
import androidx.lifecycle.LiveData
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.screensaver.R
import com.example.screensaver.databinding.LayoutErrorBinding
import com.example.screensaver.models.Album
import com.example.screensaver.models.MediaItem
import com.example.screensaver.utils.OnPhotoLoadListener
import com.example.screensaver.utils.PhotoLoadingManager
import com.example.screensaver.utils.RetryActionListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PhotoBindingAdapters {
    private const val CROSSFADE_DURATION = 300
    private const val THUMBNAIL_SIZE = 300

    private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    @JvmStatic
    @BindingAdapter("photoUrl", "quality", "onLoadingComplete", requireAll = false)
    fun loadPhoto(
        view: ImageView,
        mediaItem: MediaItem?,
        quality: Int?,
        listener: OnPhotoLoadListener?
    ) {
        if (mediaItem == null) {
            listener?.onPhotoLoadComplete(false)
            return
        }

        val url = when (quality) {
            PhotoLoadingManager.QUALITY_LOW -> mediaItem.getPreviewUrl(720)
            PhotoLoadingManager.QUALITY_MEDIUM -> mediaItem.getPreviewUrl(1080)
            PhotoLoadingManager.QUALITY_HIGH -> mediaItem.getFullQualityUrl()
            else -> mediaItem.getFullQualityUrl()
        }

        Glide.with(view.context)
            .load(url)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(false)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(true)
                    return false
                }
            })
            .into(view)

        mediaItem.updateLoadState(MediaItem.LoadState.LOADING)
    }

    @JvmStatic
    @BindingAdapter("photoUrl", "quality", "onLoadingComplete", requireAll = false)
    fun loadPhoto(
        view: ImageView,
        photoUrl: String?,
        quality: LiveData<Int>?,
        listener: OnPhotoLoadListener?
    ) {
        if (photoUrl == null) {
            listener?.onPhotoLoadComplete(false)
            return
        }

        val request = Glide.with(view.context)
            .load(photoUrl)
            .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(false)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    listener?.onPhotoLoadComplete(true)
                    return false
                }
            })

        quality?.value?.let { q ->
            when (q) {
                PhotoLoadingManager.QUALITY_LOW -> request.override(720, 720)
                PhotoLoadingManager.QUALITY_MEDIUM -> request.override(1080, 1080)
                PhotoLoadingManager.QUALITY_HIGH -> request.override(Target.SIZE_ORIGINAL)
            }
        }

        request.into(view)
    }

    @JvmStatic
    @BindingAdapter("albumThumbnail")
    fun loadAlbumThumbnail(view: ImageView, album: Album?) {
        album?.coverPhotoUrl?.let { url ->
            Glide.with(view.context)
                .load(url)
                .transition(DrawableTransitionOptions.withCrossFade(CROSSFADE_DURATION))
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_photo_placeholder)
                .error(R.drawable.ic_error)
                .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                .into(view)
        } ?: run {
            view.setImageResource(R.drawable.ic_photo_placeholder)
        }
    }

    @JvmStatic
    @BindingAdapter("android:text")
    fun setDateTime(view: TextView, date: LiveData<Date>?) {
        if (date?.value != null) {
            view.text = when (view.id) {
                R.id.textViewTime -> timeFormat.format(date.value!!)
                R.id.textViewDate -> dateFormat.format(date.value!!)
                else -> dateFormat.format(date.value!!)
            }
        } else {
            view.text = ""
        }
    }

    @JvmStatic
    @BindingAdapter("isVisible")
    fun setVisibility(view: View, isVisible: Boolean) {
        view.isVisible = isVisible
    }

    @JvmStatic
    @BindingAdapter("photoLocation")
    fun setPhotoLocation(view: TextView, mediaItem: MediaItem?) {
        view.text = mediaItem?.description.orEmpty()
        view.isVisible = !mediaItem?.description.isNullOrEmpty()
    }

    @JvmStatic
    @BindingAdapter("photoCount")
    fun setPhotoCount(view: TextView, count: Int) {
        view.text = view.context.resources.getQuantityString(
            R.plurals.photo_count,
            count,
            count
        )
    }

    @JvmStatic
    @BindingAdapter("onRetry")
    fun setOnRetryClickListener(view: View, onRetry: RetryActionListener?) {
        if (view.id == R.id.errorView) {
            LayoutErrorBinding.bind(view).retryButton.setOnClickListener {
                onRetry?.onRetry()
            }
        }
    }

    @JvmStatic
    @BindingAdapter("errorMessage")
    fun setErrorMessage(view: View, message: String?) {
        if (view.id == R.id.errorView) {
            LayoutErrorBinding.bind(view).errorMessageText.text = message
        }
    }
}