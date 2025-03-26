package com.example.screensaver.version

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProVersionPromptDialog : DialogFragment() {

    companion object {
        private const val ARG_FEATURE = "feature"
        private const val PRO_VERSION_URL = "https://play.google.com/store/apps/details?id=com.example.screensaver.pro"

        fun newInstance(feature: FeatureManager.Feature): ProVersionPromptDialog {
            return ProVersionPromptDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_FEATURE, feature)
                }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val feature = arguments?.getSerializable(ARG_FEATURE) as? FeatureManager.Feature
            ?: FeatureManager.Feature.MUSIC

        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_pro_version_prompt, null)

        // Set feature-specific content
        val titleText = view.findViewById<TextView>(R.id.pro_feature_title)
        val descriptionText = view.findViewById<TextView>(R.id.pro_feature_description)
        val featureImage = view.findViewById<ImageView>(R.id.pro_feature_image)

        when (feature) {
            FeatureManager.Feature.MUSIC -> {
                titleText.text = getString(R.string.music_feature_title)
                descriptionText.text = getString(R.string.music_feature_description)
                featureImage.setImageResource(R.drawable.ic_music_pref)
            }
            FeatureManager.Feature.WIDGETS -> {
                titleText.text = getString(R.string.widgets_feature_title)
                descriptionText.text = getString(R.string.widgets_feature_description)
                featureImage.setImageResource(R.drawable.ic_widgets_preview)
            }
            FeatureManager.Feature.SECURITY -> {
                titleText.text = getString(R.string.security_feature_title)
                descriptionText.text = getString(R.string.security_feature_description)
                featureImage.setImageResource(R.drawable.ic_security)
            }
            else -> {
                // Default case
                titleText.text = getString(R.string.pro_feature_title)
                descriptionText.text = getString(R.string.pro_feature_description)
            }
        }

        // Set up buttons
        view.findViewById<Button>(R.id.upgrade_button).setOnClickListener {
            openProVersionPage()
            dismiss()
        }

        view.findViewById<Button>(R.id.cancel_button).setOnClickListener {
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
    }

    private fun openProVersionPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRO_VERSION_URL))
        startActivity(intent)
    }
}