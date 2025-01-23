package com.example.screensaver.utils

import android.view.View
import androidx.databinding.BindingAdapter
import com.example.screensaver.databinding.LayoutErrorBinding

object BindingAdapters {
    @JvmStatic
    @BindingAdapter("app:onRetry")
    fun setOnRetryClickListener(view: View, onRetry: RetryActionListener?) {
        if (view.id == R.id.errorView) {
            val binding = LayoutErrorBinding.bind(view)
            binding.retryButton.setOnClickListener { onRetry?.onRetry() }
        }
    }

    @JvmStatic
    @BindingAdapter("app:isVisible")
    fun setVisibility(view: View, isVisible: Boolean) {
        view.visibility = if (isVisible) View.VISIBLE else View.GONE
    }

    @JvmStatic
    @BindingAdapter("app:errorMessage")
    fun setErrorMessage(view: View, message: String?) {
        if (view.id == R.id.errorView) {
            val binding = LayoutErrorBinding.bind(view)
            binding.errorMessageText.text = message
        }
    }
}