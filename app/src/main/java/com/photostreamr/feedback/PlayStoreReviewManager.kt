package com.photostreamr.feedback

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.ReviewInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PlayStoreReviewManager(private val context: Context) {

    private val reviewManager = ReviewManagerFactory.create(context)
    private val sharedPrefs = context.getSharedPreferences("app_reviews", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "PlayStoreReviewManager"
        private const val PREF_REVIEW_REQUESTED = "review_requested"
        private const val PREF_LAST_REVIEW_REQUEST = "last_review_request"
        private const val REVIEW_COOLDOWN_DAYS = 30
    }

    suspend fun requestReview(activity: Activity): Boolean {
        return try {
            if (shouldShowReviewDialog()) {
                val reviewInfo = requestReviewInfo()
                if (reviewInfo != null) {
                    launchReviewFlow(activity, reviewInfo)
                    markReviewRequested()
                    true
                } else {
                    // Fallback to Play Store
                    openPlayStorePage()
                    false
                }
            } else {
                Log.d(TAG, "Review request skipped due to cooldown")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting review", e)
            // Fallback to Play Store
            openPlayStorePage()
            false
        }
    }

    private suspend fun requestReviewInfo(): ReviewInfo? {
        return suspendCancellableCoroutine { continuation ->
            val request = reviewManager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    Log.e(TAG, "Error requesting review info", task.exception)
                    continuation.resume(null)
                }
            }
        }
    }

    private suspend fun launchReviewFlow(activity: Activity, reviewInfo: ReviewInfo): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val flow = reviewManager.launchReviewFlow(activity, reviewInfo)
            flow.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Review flow completed successfully")
                    continuation.resume(true)
                } else {
                    Log.e(TAG, "Error launching review flow", task.exception)
                    continuation.resume(false)
                }
            }
        }
    }

    private fun shouldShowReviewDialog(): Boolean {
        val lastRequest = sharedPrefs.getLong(PREF_LAST_REVIEW_REQUEST, 0)
        val daysSinceLastRequest = (System.currentTimeMillis() - lastRequest) / (1000 * 60 * 60 * 24)
        return daysSinceLastRequest >= REVIEW_COOLDOWN_DAYS
    }

    private fun markReviewRequested() {
        sharedPrefs.edit()
            .putBoolean(PREF_REVIEW_REQUESTED, true)
            .putLong(PREF_LAST_REVIEW_REQUEST, System.currentTimeMillis())
            .apply()
    }

    fun openPlayStorePage() {
        val packageName = context.packageName
        try {
            // Try to open in Play Store app
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun hasUserRatedApp(): Boolean {
        return sharedPrefs.getBoolean(PREF_REVIEW_REQUESTED, false)
    }
}