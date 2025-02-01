package com.example.screensaver.lock

import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

abstract class SwipeDismissTouchListener(private val view: View) : View.OnTouchListener {
    private var initialY = 0f
    private val SWIPE_THRESHOLD = 100

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                initialY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                val deltaY = event.rawY - initialY
                if (abs(deltaY) > SWIPE_THRESHOLD) {
                    onDismiss()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - initialY
                view.translationY = deltaY
                view.alpha = 1 - abs(deltaY) / (view.height / 2f)
                return true
            }
        }
        return false
    }

    abstract fun onDismiss()
}