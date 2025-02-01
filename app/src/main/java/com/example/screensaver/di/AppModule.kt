package com.example.screensaver.di

import android.content.Context
import com.example.screensaver.analytics.PhotoAnalytics
import com.example.screensaver.shared.GooglePhotosManager
import com.example.screensaver.utils.AppPreferences
import com.example.screensaver.utils.NotificationHelper
import com.example.screensaver.PhotoSourceState
import com.example.screensaver.ui.PhotoDisplayManager
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
import kotlinx.coroutines.runBlocking
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.data.AppDataManager
import com.example.screensaver.data.SecureStorage
import com.example.screensaver.recovery.StateRecoveryManager
import com.example.screensaver.recovery.StateRestoration
import com.example.screensaver.data.PhotoCache
import com.example.screensaver.widget.ClockWidgetManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
    fun provideClockWidgetManager(
        @ApplicationContext context: Context
    ): ClockWidgetManager {
        return ClockWidgetManager(context)
    }

    @Provides
    @Singleton
    fun provideAppPreferences(
        @ApplicationContext context: Context
    ): AppPreferences {
        return AppPreferences(context)
    }

    @Provides
    @Singleton
    fun provideGooglePhotosManager(
        @ApplicationContext context: Context,
        preferences: AppPreferences,
        secureStorage: SecureStorage
    ): GooglePhotosManager {
        return GooglePhotosManager(context, preferences, secureStorage)
    }

    @Provides
    @Singleton
    fun provideNotificationHelper(@ApplicationContext context: Context): NotificationHelper {
        return NotificationHelper(context)
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
    fun provideLockScreenPhotoManager(
        @ApplicationContext context: Context,
        googlePhotosManager: GooglePhotosManager
    ): LockScreenPhotoManager {
        return LockScreenPhotoManager(context, googlePhotosManager)
    }

    @Provides
    @Singleton
    fun providePhotoDisplayManager(
        lockScreenPhotoManager: LockScreenPhotoManager,
        photoCache: PhotoCache,
        @ApplicationContext context: Context,
        clockWidgetManager: ClockWidgetManager
    ): PhotoDisplayManager {
        return PhotoDisplayManager(
            photoManager = lockScreenPhotoManager,
            photoCache = photoCache,
            context = context,
            clockWidgetManager = clockWidgetManager
        )
    }

    @Provides
    @Singleton
    fun provideStateRecoveryManager(
        @ApplicationContext context: Context,
        appDataManager: AppDataManager
    ): StateRecoveryManager {
        return StateRecoveryManager(context, appDataManager)
    }

    @Provides
    @Singleton
    fun provideStateRestoration(
        @ApplicationContext context: Context,
        appDataManager: AppDataManager,
        secureStorage: SecureStorage
    ): StateRestoration {
        return StateRestoration(context, appDataManager, secureStorage)
    }

    @Provides
    @Singleton
    fun providePhotoSourceState(
        @ApplicationContext context: Context,
        appDataManager: AppDataManager
    ): PhotoSourceState {
        return PhotoSourceState(context, appDataManager)
    }

    @Provides
    @Singleton
    fun providePhotoCache(
        @ApplicationContext context: Context
    ): PhotoCache {
        return runBlocking {
            PhotoCache(context)
        }
    }
}