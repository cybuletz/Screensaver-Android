package com.example.screensaver.music

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MusicSourcesDialog : DialogFragment() {
    companion object {
        fun newInstance() = MusicSourcesDialog()
    }

    private var musicPreferenceFragment: MusicPreferenceFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.PhotoSourcesDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_music_sources, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        musicPreferenceFragment = MusicPreferenceFragment()
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

    override fun onDestroyView() {
        musicPreferenceFragment = null
        super.onDestroyView()
    }
}