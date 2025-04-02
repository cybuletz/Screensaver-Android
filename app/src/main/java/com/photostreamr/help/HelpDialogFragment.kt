package com.photostreamr.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import com.photostreamr.R
import com.photostreamr.tutorial.TutorialManager
import com.photostreamr.tutorial.TutorialOverlayFragment
import com.photostreamr.tutorial.TutorialType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HelpDialogFragment : DialogFragment() {

    @Inject
    lateinit var tutorialManager: TutorialManager

    companion object {
        fun newInstance(): HelpDialogFragment = HelpDialogFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.MaterialDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_help, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        view.findViewById<Button>(R.id.show_tutorial_button).setOnClickListener {
            dismiss()
            showTutorial()
        }

        view.findViewById<Button>(R.id.close_button).setOnClickListener {
            dismiss()
        }
    }

    private fun showTutorial() {
        val parentActivity = activity ?: return
        val tutorialFragment = TutorialOverlayFragment.newInstance(TutorialType.SETTINGS)

        if (parentActivity is TutorialOverlayFragment.TutorialCallback) {
            tutorialFragment.setCallback(parentActivity)
        }

        tutorialFragment.show(parentActivity.supportFragmentManager, "tutorial_overlay")
    }
}