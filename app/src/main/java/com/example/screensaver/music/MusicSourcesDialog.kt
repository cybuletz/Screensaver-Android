package com.example.screensaver.music

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

class MusicSourcesDialog : DialogFragment() {

    private var musicPreferenceFragment: MusicPreferenceFragment? = null

    companion object {
        fun newInstance(): MusicSourcesDialog {
            return MusicSourcesDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_music_sources, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicPreferenceFragment = MusicPreferenceFragment().apply {
            setOnPreferenceChangeCallback {
                // Force dialog size recalculation when preferences change
                dialog?.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
        }

        childFragmentManager
            .beginTransaction()
            .replace(R.id.music_preferences_container, musicPreferenceFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            musicPreferenceFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            musicPreferenceFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onDestroyView() {
        musicPreferenceFragment = null
        super.onDestroyView()
    }
}