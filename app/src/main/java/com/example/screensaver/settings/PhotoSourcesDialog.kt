package com.example.screensaver.settings

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import androidx.fragment.app.DialogFragment
import com.example.screensaver.R
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PhotoSourcesDialog : DialogFragment() {
    companion object {
        fun newInstance() = PhotoSourcesDialog()
    }

    private var photoSourcesFragment: PhotoSourcesPreferencesFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.PhotoSourcesDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            requestFeature(Window.FEATURE_NO_TITLE)
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

        view.findViewById<Button>(R.id.cancel_button).setOnClickListener {
            photoSourcesFragment?.cancelChanges()
            dismiss()
        }

        view.findViewById<Button>(R.id.ok_button).setOnClickListener {
            photoSourcesFragment?.applyChanges()
            dismiss()
        }
    }

    override fun onDestroyView() {
        photoSourcesFragment = null
        super.onDestroyView()
    }
}