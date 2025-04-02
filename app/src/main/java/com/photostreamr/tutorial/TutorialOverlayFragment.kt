package com.photostreamr.tutorial

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.photostreamr.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TutorialOverlayFragment : DialogFragment() {

    @Inject
    lateinit var tutorialManager: TutorialManager

    private lateinit var overlayView: TutorialOverlayView
    private lateinit var hintText: TextView
    private lateinit var nextButton: Button
    private lateinit var closeButton: Button
    private lateinit var doNotShowCheckBox: CheckBox
    private lateinit var hintContainer: View

    private var currentStep = 0
    private var tutorialCallback: TutorialCallback? = null
    private lateinit var tutorialSteps: List<TutorialStep>

    companion object {
        private const val TAG = "TutorialOverlayFragment"
        private const val ARG_TUTORIAL_TYPE = "tutorial_type"

        fun newInstance(tutorialType: TutorialType): TutorialOverlayFragment {
            val fragment = TutorialOverlayFragment()
            val args = Bundle()
            args.putSerializable(ARG_TUTORIAL_TYPE, tutorialType)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_tutorial_overlay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlayView = view.findViewById(R.id.overlay_view)
        hintText = view.findViewById(R.id.hint_text)
        nextButton = view.findViewById(R.id.next_button)
        closeButton = view.findViewById(R.id.close_button)
        doNotShowCheckBox = view.findViewById(R.id.do_not_show_checkbox)
        hintContainer = view.findViewById(R.id.hint_container)

        val tutorialType = arguments?.getSerializable(ARG_TUTORIAL_TYPE) as TutorialType
        tutorialSteps = tutorialManager.getTutorialSteps(tutorialType)

        if (tutorialSteps.isEmpty()) {
            dismiss()
            return
        }

        nextButton.setOnClickListener { moveToNextStep() }
        closeButton.setOnClickListener { closeTutorial() }

        // Start with the first step
        showStep(0)
    }

    private fun showStep(stepIndex: Int) {
        if (stepIndex < 0 || stepIndex >= tutorialSteps.size) return

        currentStep = stepIndex
        val step = tutorialSteps[stepIndex]

        // Update text
        hintText.text = step.description

        // Show/hide next button on last step
        nextButton.visibility = if (stepIndex == tutorialSteps.size - 1) View.GONE else View.VISIBLE

        // Ask the callback to focus on the target view
        tutorialCallback?.getTargetView(step.targetViewId)?.let { targetView ->
            targetView.post {
                // Get the location of target view on screen
                val location = IntArray(2)
                targetView.getLocationInWindow(location)

                // Get dimensions
                val targetRect = RectF(
                    location[0].toFloat(),
                    location[1].toFloat(),
                    (location[0] + targetView.width).toFloat(),
                    (location[1] + targetView.height).toFloat()
                )

                // Add some padding around the target
                val padding = resources.getDimensionPixelSize(R.dimen.tutorial_highlight_padding)
                targetRect.inset(-padding.toFloat(), -padding.toFloat())

                // Animate the highlight to the new position
                animateHighlight(targetRect)

                // Position the hint text container
                positionHintContainer(targetRect)
            }
        }
    }

    private fun positionHintContainer(targetRect: RectF) {
        val containerLayoutParams = hintContainer.layoutParams as ConstraintLayout.LayoutParams

        // Position the hint container based on the target view's position
        // If target is in top half, place hint at bottom, otherwise at top
        val screenHeight = resources.displayMetrics.heightPixels
        if (targetRect.centerY() < screenHeight / 2) {
            containerLayoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
            containerLayoutParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            containerLayoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            containerLayoutParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            containerLayoutParams.topMargin = (targetRect.bottom + resources.getDimensionPixelSize(R.dimen.tutorial_hint_margin)).toInt()
        } else {
            containerLayoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
            containerLayoutParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
            containerLayoutParams.topToTop = ConstraintLayout.LayoutParams.UNSET
            containerLayoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            containerLayoutParams.bottomMargin = (screenHeight - targetRect.top + resources.getDimensionPixelSize(R.dimen.tutorial_hint_margin)).toInt()
        }

        hintContainer.layoutParams = containerLayoutParams
    }

    private fun animateHighlight(targetRect: RectF) {
        // Using ObjectAnimator to animate the overlay's target rectangle
        val currentRect = overlayView.getTargetRect() ?: targetRect

        val path = Path()
        path.moveTo(currentRect.left, currentRect.top)
        path.lineTo(targetRect.left, targetRect.top)

        val animator = ObjectAnimator.ofFloat(overlayView, "targetRect", 0f, 1f)
        animator.duration = 300
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedValue as Float

            val left = currentRect.left + (targetRect.left - currentRect.left) * fraction
            val top = currentRect.top + (targetRect.top - currentRect.top) * fraction
            val right = currentRect.right + (targetRect.right - currentRect.right) * fraction
            val bottom = currentRect.bottom + (targetRect.bottom - currentRect.bottom) * fraction

            overlayView.setTargetRect(RectF(left, top, right, bottom))
        }

        animator.start()
    }

    private fun moveToNextStep() {
        if (currentStep < tutorialSteps.size - 1) {
            showStep(currentStep + 1)
        } else {
            closeTutorial()
        }
    }

    private fun closeTutorial() {
        if (doNotShowCheckBox.isChecked) {
            saveTutorialSetting()
        }
        tutorialCallback?.onTutorialClosed()
        dismiss()
    }

    private fun saveTutorialSetting() {
        val tutorialType = arguments?.getSerializable(ARG_TUTORIAL_TYPE) as TutorialType
        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
            .putBoolean("tutorial_shown_${tutorialType.name}", true)
            .apply()
    }

    fun setCallback(callback: TutorialCallback) {
        this.tutorialCallback = callback
    }

    interface TutorialCallback {
        fun getTargetView(viewId: Int): View?
        fun onTutorialClosed()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is TutorialCallback) {
            tutorialCallback = context
        }
    }

    override fun onDetach() {
        tutorialCallback = null
        super.onDetach()
    }
}