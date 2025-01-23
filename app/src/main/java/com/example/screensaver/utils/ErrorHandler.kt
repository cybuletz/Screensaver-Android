package com.example.screensaver.utils

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import com.example.screensaver.R
import com.example.screensaver.analytics.PhotoAnalytics
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Centralized error handling for the application.
 * Manages error reporting, user feedback, and error recovery.
 */
class ErrorHandler(
    private val context: Context,
    private val analytics: PhotoAnalytics
) {

    private val errorChannel = Channel<ErrorEvent>(Channel.BUFFERED)

    sealed class ErrorEvent {
        data class NetworkError(val error: Throwable) : ErrorEvent()
        data class PhotoLoadError(val error: Throwable, val photoUrl: String?) : ErrorEvent()
        data class PermissionError(val permission: String) : ErrorEvent()
        data class StorageError(val error: Throwable) : ErrorEvent()
        data class ApiError(val code: Int, val message: String?) : ErrorEvent()
        data class GeneralError(val error: Throwable) : ErrorEvent()
    }

    companion object {
        private const val DEFAULT_SNACKBAR_DURATION = 5000 // milliseconds
    }

    /**
     * Provides access to error events as a Flow
     */
    fun getErrorFlow(): Flow<ErrorEvent> = errorChannel.receiveAsFlow()

    /**
     * Handles exceptions and provides appropriate user feedback
     */
    suspend fun handleError(error: Throwable, view: View? = null) {
        when (error) {
            is CancellationException -> {
                // Ignore cancellation exceptions
                return
            }
            is UnknownHostException,
            is SocketTimeoutException,
            is IOException -> {
                handleNetworkError(error, view)
            }
            is HttpException -> {
                handleApiError(error, view)
            }
            else -> {
                handleGeneralError(error, view)
            }
        }
    }

    /**
     * Handles network-related errors
     */
    private suspend fun handleNetworkError(error: Throwable, view: View?) {
        analytics.trackNetworkError(error, NetworkUtils(context).networkState.value.connectionType.name)
        errorChannel.send(ErrorEvent.NetworkError(error))

        val message = when (error) {
            is UnknownHostException -> R.string.error_no_internet
            is SocketTimeoutException -> R.string.error_timeout
            else -> R.string.error_network_general
        }

        showError(message, view)
    }

    /**
     * Handles API-related errors
     */
    private suspend fun handleApiError(error: HttpException, view: View?) {
        val errorMessage = error.response()?.errorBody()?.string()
        errorChannel.send(ErrorEvent.ApiError(error.code(), errorMessage))

        val message = when (error.code()) {
            401 -> R.string.error_unauthorized
            403 -> R.string.error_forbidden
            404 -> R.string.error_not_found
            429 -> R.string.error_rate_limit
            500, 502, 503, 504 -> R.string.error_server
            else -> R.string.error_api_general
        }

        showError(message, view)
    }

    /**
     * Handles photo loading errors
     */
    suspend fun handlePhotoError(error: Throwable, photoUrl: String?, view: View?) {
        analytics.trackPhotoLoadError(error, photoUrl)
        errorChannel.send(ErrorEvent.PhotoLoadError(error, photoUrl))

        val message = when (error) {
            is OutOfMemoryError -> R.string.error_out_of_memory
            is IOException -> R.string.error_photo_load
            else -> R.string.error_photo_general
        }

        showError(message, view)
    }

    /**
     * Handles permission-related errors
     */
    suspend fun handlePermissionError(permission: String, view: View?) {
        errorChannel.send(ErrorEvent.PermissionError(permission))

        val message = when (permission) {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_MEDIA_IMAGES -> R.string.error_storage_permission
            android.Manifest.permission.POST_NOTIFICATIONS -> R.string.error_notification_permission
            else -> R.string.error_permission_general
        }

        showError(message, view, true)
    }

    /**
     * Handles storage-related errors
     */
    suspend fun handleStorageError(error: Throwable, view: View?) {
        errorChannel.send(ErrorEvent.StorageError(error))

        val message = when (error) {
            is IOException -> R.string.error_storage_access
            is SecurityException -> R.string.error_storage_permission
            else -> R.string.error_storage_general
        }

        showError(message, view)
    }

    /**
     * Handles general/unknown errors
     */
    private suspend fun handleGeneralError(error: Throwable, view: View?) {
        analytics.trackPhotoLoadError(error, null)
        errorChannel.send(ErrorEvent.GeneralError(error))
        showError(R.string.error_general, view)
    }

    /**
     * Shows error message to user
     */
    private fun showError(@StringRes messageRes: Int, view: View?, isLongDuration: Boolean = false) {
        val message = context.getString(messageRes)

        if (view != null) {
            showSnackbar(view, message, isLongDuration)
        } else {
            showToast(message, isLongDuration)
        }
    }

    /**
     * Shows a Snackbar with error message
     */
    private fun showSnackbar(view: View, message: String, isLongDuration: Boolean) {
        Snackbar.make(
            view,
            message,
            if (isLongDuration) Snackbar.LENGTH_LONG else DEFAULT_SNACKBAR_DURATION
        ).apply {
            setAction(R.string.action_dismiss) { dismiss() }
            show()
        }
    }

    /**
     * Shows a Toast with error message
     */
    private fun showToast(message: String, isLongDuration: Boolean) {
        Toast.makeText(
            context,
            message,
            if (isLongDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        errorChannel.close()
    }
}