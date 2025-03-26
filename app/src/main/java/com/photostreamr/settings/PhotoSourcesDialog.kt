package com.photostreamr.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import com.google.android.material.button.MaterialButton
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PhotoSourcesDialog : DialogFragment() {
    companion object {
        fun newInstance() = PhotoSourcesDialog()
    }

    private var photoSourcesFragment: PhotoSourcesPreferencesFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog) // Updated style
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // Updated window background handling
        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return inflater.inflate(R.layout.dialog_photo_sources, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        photoSourcesFragment = PhotoSourcesPreferencesFragment()
        childFragmentManager
            .beginTransaction()
            .replace(R.id.photo_sources_container, photoSourcesFragment!!)
            .commit()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            photoSourcesFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
            photoSourcesFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        photoSourcesFragment = null
        super.onDestroyView()
    }
}