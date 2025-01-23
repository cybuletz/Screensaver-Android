package com.example.screensaver.di

import android.content.Context
import com.example.screensaver.shared.GooglePhotosManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ManagerModule {
    @Provides
    @Singleton
    fun provideGooglePhotosManager(
        @ApplicationContext context: Context
    ): GooglePhotosManager {
        return GooglePhotosManager.getInstance(context)
    }
}