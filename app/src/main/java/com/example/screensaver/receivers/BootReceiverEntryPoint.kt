package com.example.screensaver.receivers

import com.example.screensaver.data.AppDataManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BootReceiverEntryPoint {
    fun getAppDataManager(): AppDataManager
}