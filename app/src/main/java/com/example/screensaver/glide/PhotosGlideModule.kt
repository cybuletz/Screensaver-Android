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
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.shared.HasGooglePhotosManager
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
        fun secureStorage(): SecureStorage
        fun googlePhotosManager(): GooglePhotosManager
        fun photoUriManager(): PhotoUriManager
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GlideModuleEntryPoint::class.java
        )

        val secureStorage = entryPoint.secureStorage()
        val googlePhotosManager = entryPoint.googlePhotosManager()
        val photoUriManager = entryPoint.photoUriManager()

        // Create OkHttpClient with specialized interceptor for Google Photos URIs
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .addInterceptor(GooglePhotosAuthInterceptor(secureStorage, googlePhotosManager))
            .build()

        // Register our custom URI loader factory
        registry.prepend(
            Uri::class.java,
            InputStream::class.java,
            UriLoaderFactory(context, photoUriManager, client)
        )

        // Also keep the standard OkHttpUrlLoader for GlideUrls
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
    private class GooglePhotosAuthInterceptor(
        private val secureStorage: SecureStorage,
        private val googlePhotosManager: GooglePhotosManager
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()

            // Only handle Google Photos URLs
            if (url.contains("googleusercontent.com")) {
                try {
                    // Ensure token is fresh before proceeding
                    runBlocking {
                        if (!googlePhotosManager.hasValidTokens()) {
                            googlePhotosManager.refreshTokens()
                        }
                    }

                    val credentials = secureStorage.getGoogleCredentials()
                    val newRequest = originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer ${credentials?.accessToken}")
                        .build()

                    var response = chain.proceed(newRequest)

                    // If we get a 403, try refreshing the token once
                    if (response.code == 403) {
                        response.close()
                        runBlocking {
                            googlePhotosManager.refreshTokens()
                        }
                        val freshCredentials = secureStorage.getGoogleCredentials()
                        val retryRequest = originalRequest.newBuilder()
                            .addHeader("Authorization", "Bearer ${freshCredentials?.accessToken}")
                            .build()
                        response = chain.proceed(retryRequest)
                    }

                    return response
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling Google Photos auth", e)
                    throw e
                }
            }

            // For all other URLs, proceed normally
            return chain.proceed(originalRequest)
        }
    }

    /**
     * Factory for creating UriLoader instances
     */
    private class UriLoaderFactory(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : ModelLoaderFactory<Uri, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Uri, InputStream> {
            return UriLoader(context, photoUriManager, client)
        }

        override fun teardown() {
            // Nothing to clean up
        }
    }

    /**
     * Custom loader for handling different URI types
     */
    private class UriLoader(
        private val context: Context,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : ModelLoader<Uri, InputStream> {
        override fun buildLoadData(uri: Uri, width: Int, height: Int, options: Options): ModelLoader.LoadData<InputStream>? {
            // Create a proper GlideKey for the LoadData constructor
            val signature = ObjectKey(uri.toString())
            return ModelLoader.LoadData(signature, UriDataFetcher(context, uri, photoUriManager, client))
        }

        override fun handles(uri: Uri): Boolean = true
    }

    /**
     * Custom DataFetcher for handling different URI types
     */
    /**
     * Custom DataFetcher for handling different URI types across Android versions
     */
    private class UriDataFetcher(
        private val context: Context,
        private val uri: Uri,
        private val photoUriManager: PhotoUriManager,
        private val client: OkHttpClient
    ) : DataFetcher<InputStream> {
        private var stream: InputStream? = null
        private var cancelled = false

        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                Log.d(TAG, "Loading image from URI: $uri")

                // Check if we have valid permissions - this will catch basic permission issues
                if (!photoUriManager.hasValidPermission(uri)) {
                    throw SecurityException("No permission to access $uri")
                }

                // Determine the appropriate loading method based on URI type and Android version
                when {
                    // Case 1: Google Photos URIs
                    uri.toString().contains("com.google.android.apps.photos") -> {
                        handleGooglePhotosUri(callback)
                    }

                    // Case 2: Android 13+ Photo Picker URIs
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            uri.toString().contains("com.android.providers.media.photopicker") -> {
                        handleMediaPickerUri(callback)
                    }

                    // Case 3: All other content URIs
                    else -> {
                        handleContentUri(callback)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading URI: $uri", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        /**
         * Handle Google Photos URIs - these need special OAuth handling on Android 11+
         */
        private fun handleGooglePhotosUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val uriString = uri.toString()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // On Android 11+, Google Photos URIs require special handling

                    // First try the standard content resolver approach
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            stream = inputStream
                            if (!cancelled) {
                                callback.onDataReady(stream)
                            }
                            return
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Security exception trying direct access, falling back to OAuth", e)
                        // Continue to OAuth approach below
                    }

                    // If direct access fails, we need to use OAuth with Google Photos API
                    // This requires the GooglePhotosManager to handle authentication
                    val googlePhotosManager = getGooglePhotosManager(context)

                    // Get the auth token using the entryPoint directly instead of getAuthToken
                    val authToken = runBlocking {
                        val entryPoint = EntryPointAccessors.fromApplication(
                            context.applicationContext,
                            GlideModuleEntryPoint::class.java
                        )
                        val secureStorage = entryPoint.secureStorage()
                        secureStorage.getGoogleCredentials()?.accessToken
                    } ?: run {
                        throw SecurityException("No auth token available for Google Photos")
                    }

                    // Extract the mediaId from the URI if possible
                    val mediaId = extractMediaIdFromUri(uriString)

                    val baseUrl = "https://photoslibrary.googleapis.com/v1/mediaItems/$mediaId"
                    val request = Request.Builder()
                        .url(baseUrl)
                        .addHeader("Authorization", "Bearer $authToken")
                        .build()

                    // Execute the network request
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val mediaData = JSONObject(response.body?.string() ?: "{}")
                            val downloadUrl = mediaData.optString("baseUrl")

                            if (downloadUrl.isNotEmpty()) {
                                // Now fetch the actual image data
                                val imageRequest = Request.Builder()
                                    .url("$downloadUrl=w${DOWNLOAD_SIZE}-h${DOWNLOAD_SIZE}")
                                    .build()

                                client.newCall(imageRequest).execute().use { imageResponse ->
                                    if (imageResponse.isSuccessful) {
                                        stream = imageResponse.body?.byteStream()
                                        if (!cancelled) {
                                            callback.onDataReady(stream)
                                        }
                                        return
                                    } else {
                                        throw IOException("Failed to load image: ${imageResponse.code}")
                                    }
                                }
                            } else {
                                throw IOException("No download URL in Google Photos response")
                            }
                        } else {
                            throw IOException("Failed to get media item: ${response.code}")
                        }
                    }
                } else {
                    // On older Android versions, try direct content resolver access
                    handleContentUri(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling Google Photos URI", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        /**
         * Handle media picker URIs from Android 13+
         */
        private fun handleMediaPickerUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                // For Android 13+, we need to use the photo picker specific permission handling
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // First try with FLAG_GRANT_READ_URI_PERMISSION
                    try {
                        // Try to take persistable permissions if possible
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not take persistable permission for $uri", e)
                        // Continue anyway
                    }

                    // Now try to open the stream
                    val inputStream = context.contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                        stream = inputStream
                        if (!cancelled) {
                            callback.onDataReady(stream)
                        }
                        return
                    } else {
                        throw IOException("Could not open input stream for $uri")
                    }
                } else {
                    // Should not reach here - just fall back to standard content handling
                    handleContentUri(callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling media picker URI", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        /**
         * Handle standard content URIs
         */
        private fun handleContentUri(callback: DataFetcher.DataCallback<in InputStream>) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    stream = inputStream
                    if (!cancelled) {
                        callback.onDataReady(stream)
                    }
                } else {
                    throw IOException("Could not open input stream for $uri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling content URI", e)
                if (!cancelled) {
                    callback.onLoadFailed(e)
                }
            }
        }

        /**
         * Extract media ID from Google Photos URI
         */
        private fun extractMediaIdFromUri(uriString: String): String {
            // Example format: content://com.google.android.apps.photos.contentprovider/.../mediakey:local%3A12345/...
            val mediaKeyPattern = "mediakey[^/]+/([^/]+)".toRegex()
            val match = mediaKeyPattern.find(uriString)
            return match?.groupValues?.getOrNull(1) ?:
            throw IllegalArgumentException("Could not extract media ID from URI: $uriString")
        }

        /**
         * Get a GooglePhotosManager instance for OAuth handling
         */
        private fun getGooglePhotosManager(context: Context): GooglePhotosManager? {
            // First try EntryPoint approach
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    GlideModuleEntryPoint::class.java
                )
                return entryPoint.googlePhotosManager()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting GooglePhotosManager from EntryPoint", e)

                // Fall back to interface approach
                val application = context.applicationContext
                if (application is HasGooglePhotosManager) {
                    return application.provideGooglePhotosManager()
                }
            }

            return null
        }

        override fun cleanup() {
            try {
                stream?.close()
            } catch (e: IOException) {
                // Ignore
            }
        }

        override fun cancel() {
            cancelled = true
        }

        override fun getDataClass(): Class<InputStream> {
            return InputStream::class.java
        }

        override fun getDataSource(): DataSource {
            return DataSource.REMOTE
        }

        companion object {
            private const val DOWNLOAD_SIZE = 2048
        }
    }
}