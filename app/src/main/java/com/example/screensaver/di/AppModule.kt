package com.example.screensaver.di

import android.content.Context
import com.example.screensaver.analytics.PhotoAnalytics
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.utils.NotificationHelper
import com.example.screensaver.PhotoSourceState
import com.example.screensaver.ui.PhotoDisplayManager
import com.example.screensaver.lock.PhotoManager          // Updated import from lock package
import com.example.screensaver.utils.PhotoLoadingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    @Singleton
    @Provides
    fun provideAppPreferences(@ApplicationContext context: Context): AppPreferences {
        return AppPreferences(context)
    }

    @Provides
    @Singleton
    fun provideGooglePhotosManager(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope,
        preferences: AppPreferences
    ): GooglePhotosManager {
        return GooglePhotosManager(context, preferences, coroutineScope)
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

    @Provides
    @Singleton
    fun providePhotoLoadingManager(
        @ApplicationContext context: Context,
        coroutineScope: CoroutineScope
    ): PhotoLoadingManager {
        return PhotoLoadingManager(context, coroutineScope)
    }

    @Provides
    @Singleton
    fun providePhotoManager(
        @ApplicationContext context: Context,
        photoLoadingManager: PhotoLoadingManager
    ): PhotoManager {
        return PhotoManager(context, photoLoadingManager)
    }

    @Provides
    @Singleton
    fun providePhotoDisplayManager(
        photoManager: PhotoManager,
        @ApplicationContext context: Context
    ): PhotoDisplayManager {
        return PhotoDisplayManager(photoManager, context)
    }
}