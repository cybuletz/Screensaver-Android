package com.example.screensaver.photomanager.transitions

import android.content.Context
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionSet
import com.google.android.material.transition.MaterialSharedAxis

class SharedAxisTransitionSet(context: Context, forward: Boolean = true) : TransitionSet() {
    init {
        ordering = ORDERING_SEQUENTIAL
        addTransition(MaterialSharedAxis(MaterialSharedAxis.Z, forward))
        addTransition(ChangeBounds())
        addTransition(Fade())
    }
}