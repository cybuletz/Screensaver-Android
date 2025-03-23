package com.example.screensaver.glide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.photos.PhotoUriManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.Priority
import com.bumptech.glide.signature.ObjectKey
import com.example.screensaver.shared.GoogleAuthManager
import org.json.JSONObject

@GlideModule
class PhotosGlideModule : AppGlideModule() {
    companion object {
        private const val TAG = "PhotosGlideModule"
        private const val CONNECTION_TIMEOUT = 20L // seconds
        private const val READ_TIMEOUT = 20L // seconds
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GlideModuleEntryPoint {
        fun photoUriManager(): PhotoUriManager
        fun googleAuthManager(): GoogleAuthManager
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GlideModuleEntryPoint::class.java
        )

        val photoUriManager = entryPoint.photoUriManager()
        val googleAuthManager = entryPoint.googleAuthManager()

        // Create OkHttpClient with auth interceptor
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(GoogleAuthInterceptor(googleAuthManager))
            .build()

        // Register URI loader
        registry.prepend(
            Uri::class.java,
            InputStream::class.java,
            UriLoaderFactory(context, photoUriManager, client)
        )

        // Standard URL loader
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false

    /**
     * Custom interceptor for handling Google Photos authentication
     */
    private class GoogleAuthInterceptor(
        private val googleAuthManager: GoogleAuthManager
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            if (url.contains("googleusercontent.com")) {
                try {
                    // First check if credentials are available before attempting any operations
                    val credentials = googleAuthManager.getCurrentCredentials()

                    if (credentials == null) {
                        Log.d(TAG, "No Google credentials available, proceeding without authentication")
                        return chain.proceed(request)
                    }

                    runBlocking {
                        when (googleAuthManager.authState.value) {
                            GoogleAuthManager.AuthState.ERROR -> {
                                // Try to initialize auth
                                if (!googleAuthManager.initialize()) {
                                    throw IOException("Authentication required")
                                }
                            }
                            GoogleAuthManager.AuthState.IDLE -> {
                                if (!googleAuthManager.initialize()) {
                                    throw IOException("Failed to initialize auth")
                                }
                            }
                            else -> {
                                if (!googleAuthManager.hasValidTokens() && !googleAuthManager.refreshTokens()) {
                                    throw IOException("Failed to refresh tokens")
                                }
                            }
                        }
                    }

                    // Get fresh credentials after potential refresh
                    val updatedCredentials = googleAuthManager.getCurrentCredentials()
                        ?: throw IOException("No credentials available")

                    val newRequest = request.newBuilder()
                        .addHeader("Authorization", "Bearer ${updatedCredentials.accessToken}")
                        .build()

                    return chain.proceed(newRequest)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling Google auth, proceeding without authentication", e)
                    // Try proceeding with the request without authentication rather than failing completely
                    return chain.proceed(request)
                }
            }

            return chain.proceed(request)
        }
    }

    private class UriLoaderFactory(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : ModelLoaderFactory<Uri, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, InputStream> {
            return UriLoader(context, photoUriManager, client)
        }

        override fun teardown() {}
    }

    private class UriLoader(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : ModelLoader<Uri, InputStream> {
        override fun buildLoadData(uri: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
            val signature = ObjectKey(uri.toString())
            return ModelLoader.LoadData(signature, UriDataFetcher(context, uri))
        }

        override fun handles(uri: Uri): Boolean = true
    }

    private class UriDataFetcher(
        private val context: Context,
        private val uri: Uri
    ) : DataFetcher<InputStream> {

        companion object {
            private const val TAG = "UriDataFetcher"
            private const val MAX_RETRIES = 2
        }

        private var inputStream: InputStream? = null
        private var retryCount = 0

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // First try to get persistable permission if needed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    uri.toString().contains("com.google.android.apps.photos")
                ) {
                    // For Google Photos URIs on Android 11+, use a different approach
                    loadGooglePhotosUri(callback)
                    return
                }

                // For other URIs, try normal access
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        inputStream = stream
                        callback.onDataReady(stream)
                    } else {
                        callback.onLoadFailed(IOException("Could not open input stream for URI: $uri"))
                    }
                } catch (e: SecurityException) {
                    // If security exception, try alternative access
                    handleAlternativeAccess(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading URI: $uri", e)
                callback.onLoadFailed(e)
            }
        }

        private fun loadGooglePhotosUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // First try direct access
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        inputStream = stream
                        callback.onDataReady(stream)
                        return
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Direct access failed for Google Photos URI, trying to refresh permissions: $uri")
                }

                // If direct access fails, try with explicit permissions
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setDataAndType(uri, "image/*")
                }

                // Grant fresh permissions
                try {
                    context.grantUriPermission(
                        context.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to grant permissions for URI: $uri", e)
                }

                // Try again with fresh permissions
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    inputStream = stream
                    callback.onDataReady(stream)
                    return
                }

                // If still failed, but we have more retries left
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    Log.d(TAG, "Retry attempt $retryCount for URI: $uri")
                    loadGooglePhotosUri(callback)
                    return
                }

                callback.onLoadFailed(IOException("Could not open Google Photos URI after $MAX_RETRIES retries"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Google Photos URI: $uri", e)
                callback.onLoadFailed(e)
            }
        }

        private fun handleAlternativeAccess(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // Try to take persistable permission first
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not take persistable permission for $uri", e)
                }

                // Try to open the stream again
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    inputStream = stream
                    callback.onDataReady(stream)
                } else {
                    callback.onLoadFailed(IOException("Could not open input stream for URI: $uri"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Alternative access failed for URI: $uri", e)
                callback.onLoadFailed(e)
            }
        }

        override fun cleanup() {
            try {
                inputStream?.close()
            } catch (e: IOException) {
                // Ignore
            }
        }

        override fun cancel() {
            cleanup()
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}