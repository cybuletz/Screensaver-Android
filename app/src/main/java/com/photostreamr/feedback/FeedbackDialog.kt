package com.photostreamr.feedback

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RatingBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photostreamr.R

class FeedbackDialog : DialogFragment() {

    private lateinit var ratingBar: RatingBar
    private lateinit var feedbackText: EditText
    private lateinit var submitButton: Button
    private lateinit var rateOnPlayStoreButton: Button

    companion object {
        fun newInstance(): FeedbackDialog {
            return FeedbackDialog()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_feedback, null)

        setupViews(view)
        setupListeners()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Share Your Feedback")
            .setView(view)
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }

    private fun setupViews(view: View) {
        ratingBar = view.findViewById(R.id.rating_bar)
        feedbackText = view.findViewById(R.id.feedback_text)
        submitButton = view.findViewById(R.id.submit_feedback_button)
        rateOnPlayStoreButton = view.findViewById(R.id.rate_play_store_button)
    }

    private fun setupListeners() {
        ratingBar.setOnRatingBarChangeListener { _, rating, _ ->
            when {
                rating >= 4.0f -> {
                    // High rating - encourage Play Store review
                    rateOnPlayStoreButton.visibility = View.VISIBLE
                    feedbackText.hint = "Tell us what you love about the app!"
                }
                rating >= 2.0f -> {
                    // Medium rating - ask for feedback
                    rateOnPlayStoreButton.visibility = View.GONE
                    feedbackText.hint = "How can we improve your experience?"
                }
                rating > 0.0f -> {
                    // Low rating - ask for specific feedback
                    rateOnPlayStoreButton.visibility = View.GONE
                    feedbackText.hint = "We'd love to know what went wrong and how we can fix it."
                }
                else -> {
                    rateOnPlayStoreButton.visibility = View.GONE
                    feedbackText.hint = "Share your thoughts about the app..."
                }
            }
        }

        submitButton.setOnClickListener {
            submitFeedback()
        }

        rateOnPlayStoreButton.setOnClickListener {
            openPlayStore()
        }
    }

    private fun submitFeedback() {
        val rating = ratingBar.rating
        val feedback = feedbackText.text.toString().trim()

        if (rating == 0.0f) {
            Toast.makeText(requireContext(), "Please provide a rating", Toast.LENGTH_SHORT).show()
            return
        }

        // Create email intent with feedback
        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("photostreamr.app@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, "Screensaver App Feedback - Rating: ${rating.toInt()}/5")
            putExtra(Intent.EXTRA_TEXT, buildFeedbackEmail(rating, feedback))
        }

        try {
            startActivity(Intent.createChooser(emailIntent, "Send Feedback"))
            dismiss()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildFeedbackEmail(rating: Float, feedback: String): String {
        return """
            Rating: ${rating.toInt()}/5 stars
            
            Feedback:
            $feedback
            
            ---
            App Version: ${getAppVersion()}
            Device: ${android.os.Build.MODEL}
            Android Version: ${android.os.Build.VERSION.RELEASE}
        """.trimIndent()
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }

    private fun openPlayStore() {
        val packageName = requireContext().packageName
        try {
            // Try to open in Play Store app
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
            startActivity(intent)
        }
        dismiss()
    }
}