package com.example.screensaver.di

import android.content.Context
import com.example.screensaver.analytics.PhotoAnalytics
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.NotificationHelper
import com.example.screensaver.PhotoSourceState


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @Singleton
    @Provides
    fun provideContext(@ApplicationContext appContext: Context): Context {
        return appContext
    }

    @Singleton
    @Provides
    fun providePhotoAnalytics(@ApplicationContext context: Context): PhotoAnalytics {
        return PhotoAnalytics(context)
    }

    @Provides
    @Singleton
    fun provideGooglePhotosManager(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope
    ): GooglePhotosManager {
        return GooglePhotosManager(context, coroutineScope)
    }

    @Provides
    @Singleton
    fun provideNotificationHelper(@ApplicationContext context: Context): NotificationHelper {
        return NotificationHelper(context)
    }

    @Provides
    @Singleton
    fun providePhotoSourceState(@ApplicationContext context: Context): PhotoSourceState {
        return PhotoSourceState(context)
    }

}