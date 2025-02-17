package com.example.screensaver.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CacheCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Implement cache cleanup logic
        return Result.success()
    }
}
