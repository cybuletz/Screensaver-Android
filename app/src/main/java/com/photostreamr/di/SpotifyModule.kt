package com.photostreamr.di

import android.content.Context
import com.photostreamr.music.SpotifyManager
import com.photostreamr.music.SpotifyAuthManager
import com.photostreamr.music.SpotifyPreferences
import com.photostreamr.data.SecureStorage
import com.photostreamr.music.RadioManager
import com.photostreamr.music.RadioPreferences
import com.photostreamr.music.SpotifyTokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SpotifyModule {
    @Provides
    @Singleton
    fun provideSpotifyPreferences(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): SpotifyPreferences {
        return SpotifyPreferences(context, secureStorage)
    }

    @Provides
    @Singleton
    fun provideSpotifyAuthManager(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage,
        spotifyPreferences: SpotifyPreferences
    ): SpotifyAuthManager {
        return SpotifyAuthManager(context, secureStorage, spotifyPreferences)
    }

    @Provides
    @Singleton
    fun provideSpotifyTokenManager(
        authManager: SpotifyAuthManager
    ): SpotifyTokenManager {
        return authManager
    }

    @Provides
    @Singleton
    fun provideSpotifyManager(
        @ApplicationContext context: Context,
        spotifyPreferences: SpotifyPreferences,
        secureStorage: SecureStorage,
        tokenManager: SpotifyTokenManager
    ): SpotifyManager {
        return SpotifyManager(
            context,
            spotifyPreferences,
            secureStorage,
            tokenManager
        )
    }

    @Provides
    @Singleton
    fun provideRadioPreferences(
        @ApplicationContext context: Context,
        secureStorage: SecureStorage
    ): RadioPreferences {
        return RadioPreferences(context, secureStorage)
    }

    @Provides
    @Singleton
    fun provideRadioManager(
        @ApplicationContext context: Context,
        radioPreferences: RadioPreferences
    ): RadioManager {
        return RadioManager(context, radioPreferences)
    }
}