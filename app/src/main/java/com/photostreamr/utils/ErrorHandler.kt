package com.photostreamr.utils

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
import javax.inject.Inject
import javax.inject.Singleton
/**
 * Centralized error handling for the application.
 * Manages error reporting, user feedback, and error recovery.
 */
@Singleton
class ErrorHandler @Inject constructor(
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

        val messageRes = when (error) {
            is UnknownHostException -> R.string.error_network_connection
            is SocketTimeoutException -> R.string.error_network_timeout
            else -> R.string.error_network_general
        }

        showError(messageRes, view)
    }

    /**
     * Handles API-related errors
     */
    private suspend fun handleApiError(error: HttpException, view: View?) {
        val errorMessage = error.response()?.errorBody()?.string()
        errorChannel.send(ErrorEvent.ApiError(error.code(), errorMessage))

        val messageRes = when (error.code()) {
            401 -> R.string.error_auth_unauthorized
            403 -> R.string.error_auth_forbidden
            404 -> R.string.error_not_found
            429 -> R.string.error_rate_limit
            500, 502, 503, 504 -> R.string.error_server
            else -> R.string.error_api_general
        }

        showError(messageRes, view)
    }

    /**
     * Handles permission-related errors
     */
    suspend fun handlePermissionError(permission: String, view: View?) {
        errorChannel.send(ErrorEvent.PermissionError(permission))

        val messageRes = when (permission) {
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_MEDIA_IMAGES -> R.string.error_permission_storage
            android.Manifest.permission.POST_NOTIFICATIONS -> R.string.error_permission_notification
            else -> R.string.error_permission_general
        }

        showError(messageRes, view, true)
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
            setAction(android.R.string.ok) { dismiss() }
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