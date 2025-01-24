package com.example.screensaver.di

import com.example.screensaver.shared.GooglePhotosManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)  // Changed to SingletonComponent since GooglePhotosManager is @Singleton
object SettingsModule {
    @Provides
    @Singleton  // Add this since GooglePhotosManager is @Singleton
    fun provideGooglePhotosManager(): GooglePhotosManager {
        return GooglePhotosManager()
    }
}