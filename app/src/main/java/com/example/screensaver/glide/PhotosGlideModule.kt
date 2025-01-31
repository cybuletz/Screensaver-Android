package com.example.screensaver.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.example.screensaver.data.SecureStorage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.InputStream
import java.util.concurrent.TimeUnit

@GlideModule
class PhotosGlideModule : AppGlideModule() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface GlideModuleEntryPoint {
        fun secureStorage(): SecureStorage
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GlideModuleEntryPoint::class.java
        )

        val secureStorage = entryPoint.secureStorage()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                // Get a fresh token for each request
                val credentials = secureStorage.getGoogleCredentials()

                val originalRequest = chain.request()

                // Only add auth header for Google Photos URLs
                val request = if (originalRequest.url.toString().contains("googleusercontent.com")) {
                    originalRequest.newBuilder()
                        .addHeader("Authorization", "Bearer ${credentials?.accessToken}")
                        .build()
                } else originalRequest

                chain.proceed(request)
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        registry.replace(GlideUrl::class.java, InputStream::class.java,
            OkHttpUrlLoader.Factory(client))
    }

    override fun isManifestParsingEnabled(): Boolean = false
}