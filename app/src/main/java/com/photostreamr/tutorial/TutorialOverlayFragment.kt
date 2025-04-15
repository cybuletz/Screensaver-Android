    package com.photostreamr.tutorial

    import android.content.Context
    import android.graphics.RectF
    import android.os.Bundle
    import android.util.Log
    import android.view.LayoutInflater
    import android.view.View
    import android.view.ViewGroup
    import android.widget.Button
    import android.widget.CheckBox
    import android.widget.TextView
    import androidx.constraintlayout.widget.ConstraintLayout
    import androidx.fragment.app.DialogFragment
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

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
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

            // Set checkbox to be checked by default
            doNotShowCheckBox.isChecked = true

            val tutorialType = arguments?.getSerializable(ARG_TUTORIAL_TYPE) as TutorialType
            tutorialSteps = tutorialManager.getTutorialSteps(tutorialType)

            if (tutorialSteps.isEmpty()) {
                dismiss()
                return
            }

            nextButton.setOnClickListener { moveToNextStep() }
            closeButton.setOnClickListener { closeTutorial() }

            // Start with the first step with a delay to ensure the UI is fully prepared
            view.postDelayed({
                showStep(0)
            }, 500)
        }

        private fun showStep(stepIndex: Int) {
            if (stepIndex < 0 || stepIndex >= tutorialSteps.size) return

            currentStep = stepIndex
            val step = tutorialSteps[stepIndex]

            // Update text
            hintText.text = step.description

            // Show/hide next button on last step
            nextButton.visibility = if (stepIndex == tutorialSteps.size - 1) View.GONE else View.VISIBLE

            // Immediately find and highlight the target view - no delay
            findAndHighlightTargetView(step.targetViewId)
        }

        private fun findAndHighlightTargetView(viewId: Int) {
            Log.d(TAG, "Trying to find view for ID: $viewId")

            // Get the preference key based on the tutorial ID
            val prefKey = when (viewId) {
                TutorialManager.ID_MANAGE_PHOTOS -> "manage_photos"
                TutorialManager.ID_MUSIC_SOURCES -> "music_sources"
                TutorialManager.ID_COMMON_SETTINGS -> "common_settings"
                TutorialManager.ID_WIDGETS_SETTINGS -> "widgets_settings"
                TutorialManager.ID_DISPLAY_SETTINGS -> "display_settings"
                TutorialManager.ID_SECURITY_PREFERENCES -> "security_preferences"
                else -> null
            }

            if (prefKey != null) {
                Log.d(TAG, "Looking for preference with key: $prefKey")

                // Ask the fragment to scroll to make the preference visible if needed
                tutorialCallback?.scrollToPreference(prefKey)

                // Use post instead of postDelayed to minimize delay
                view?.post {
                    // Now try to find the view
                    val targetView = tutorialCallback?.getTargetView(viewId)
                    if (targetView != null) {
                        Log.d(TAG, "Found target view: $targetView")
                        highlightView(targetView)
                    } else {
                        Log.e(TAG, "No view found for preference $prefKey, showing fallback")
                        showFallbackHighlight()
                    }
                }
            } else {
                showFallbackHighlight()
            }
        }

        fun highlightView(targetView: View) {
            if (targetView != null) {
                findAndHighlightTargetView(targetView)
            }
        }

        private fun findAndHighlightTargetView(targetView: View) {
            Log.d(TAG, "Target view: ${targetView.javaClass.simpleName}, width=${targetView.width}, height=${targetView.height}")

            // First, we need to find the root view that contains both our overlay and the target view
            val rootView = dialog?.window?.decorView ?: return

            // Get coordinates relative to the window
            val targetLocation = IntArray(2)
            targetView.getLocationInWindow(targetLocation)

            // Get the overlay's coordinates in the same coordinate system
            val overlayLocation = IntArray(2)
            overlayView.getLocationInWindow(overlayLocation)

            // Calculate the target rect with coordinates adjusted relative to our overlay view
            val targetRect = RectF(
                (targetLocation[0] - overlayLocation[0]).toFloat(),
                (targetLocation[1] - overlayLocation[1]).toFloat(),
                (targetLocation[0] - overlayLocation[0] + targetView.width).toFloat(),
                (targetLocation[1] - overlayLocation[1] + targetView.height).toFloat()
            )

            Log.d(TAG, "Target location in window: (${targetLocation[0]}, ${targetLocation[1]})")
            Log.d(TAG, "Overlay location in window: (${overlayLocation[0]}, ${overlayLocation[1]})")
            Log.d(TAG, "Adjusted target rect: $targetRect")

            // Verify the rect is valid
            if (targetRect.width() <= 0 || targetRect.height() <= 0) {
                Log.e(TAG, "Invalid target rect with zero dimension: $targetRect")
                showFallbackHighlight()
                return
            }

            // Add padding
            val padding = resources.getDimensionPixelSize(R.dimen.tutorial_highlight_padding)
            targetRect.inset(-padding.toFloat(), -padding.toFloat())

            Log.d(TAG, "Final highlighting rect with padding: $targetRect")

            // Animate to new position
            animateHighlight(targetRect)

            // Position hint container
            positionHintContainer(targetRect)
        }

        private fun showFallbackHighlight() {
            // Use a fallback rectangle in the center of the screen
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            val fallbackRect = RectF(
                screenWidth * 0.2f,
                screenHeight * 0.3f,
                screenWidth * 0.8f,
                screenHeight * 0.4f
            )

            Log.d(TAG, "Using fallback highlight at: $fallbackRect")

            animateHighlight(fallbackRect)
            positionHintContainer(fallbackRect)
        }

        private fun positionHintContainer(targetRect: RectF) {
            val containerLayoutParams = hintContainer.layoutParams as ConstraintLayout.LayoutParams

            // Position the hint container based on the target view's position
            val screenHeight = resources.displayMetrics.heightPixels

            // Use more distinct positioning to avoid overlap
            if (targetRect.centerY() < screenHeight / 2) {
                // Target is in the top half of the screen, place hint at the bottom
                containerLayoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
                containerLayoutParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                containerLayoutParams.topToTop = ConstraintLayout.LayoutParams.UNSET
                containerLayoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                containerLayoutParams.topMargin = 0
                containerLayoutParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.tutorial_hint_margin)
            } else {
                // Target is in the bottom half of the screen, place hint at the top
                containerLayoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
                containerLayoutParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                containerLayoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                containerLayoutParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                containerLayoutParams.topMargin = resources.getDimensionPixelSize(R.dimen.tutorial_hint_margin)
                containerLayoutParams.bottomMargin = 0
            }

            // Add horizontal centering
            containerLayoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            containerLayoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID

            // Set horizontal margins instead of using the non-existent horizontalMargin property
            containerLayoutParams.marginStart = resources.getDimensionPixelSize(R.dimen.tutorial_hint_margin)
            containerLayoutParams.marginEnd = resources.getDimensionPixelSize(R.dimen.tutorial_hint_margin)

            // Ensure width is appropriate
            containerLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT

            hintContainer.layoutParams = containerLayoutParams

            // Force layout update to ensure positioning is applied immediately
            hintContainer.requestLayout()
        }

        private fun animateHighlight(targetRect: RectF) {
            val currentRect = overlayView.getTargetRect()

            if (currentRect == null) {
                // If there's no current rect, just set it immediately
                overlayView.setTargetRect(targetRect)
                return
            }

            Log.d(TAG, "Animating highlight from $currentRect to $targetRect")

            // Use ValueAnimator which doesn't try to access the property directly
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 150 // Reduced from 300ms to 150ms
            animator.addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float

                val left = currentRect.left + (targetRect.left - currentRect.left) * fraction
                val top = currentRect.top + (targetRect.top - currentRect.top) * fraction
                val right = currentRect.right + (targetRect.right - currentRect.right) * fraction
                val bottom = currentRect.bottom + (targetRect.bottom - currentRect.bottom) * fraction

                val newRect = RectF(left, top, right, bottom)
                Log.d(TAG, "Animation frame: $newRect")
                overlayView.setTargetRect(newRect)
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
                // Use the new method to disable the tutorial
                val tutorialType = arguments?.getSerializable(ARG_TUTORIAL_TYPE) as TutorialType
                tutorialManager.disableTutorial(tutorialType)
            }
            tutorialCallback?.onTutorialClosed()
            dismiss()
        }

        fun setCallback(callback: TutorialCallback) {
            this.tutorialCallback = callback
        }

        interface TutorialCallback {
            fun getTargetView(viewId: Int): View?
            fun scrollToPreference(preferenceKey: String)
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