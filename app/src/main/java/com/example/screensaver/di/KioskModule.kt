package com.example.screensaver.di

import android.content.Context
import com.example.screensaver.kiosk.KioskPolicyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KioskModule {
    @Provides
    @Singleton
    fun provideKioskPolicyManager(@ApplicationContext context: Context): KioskPolicyManager {
        return KioskPolicyManager(context)
    }
}