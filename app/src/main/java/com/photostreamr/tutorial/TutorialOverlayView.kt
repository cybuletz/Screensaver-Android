package com.photostreamr.tutorial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.util.Log

class TutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = 0xCC000000.toInt() // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val transparentPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    private var drawCount = 0
    private var lastTargetRect: RectF? = null

    private var targetRect: RectF? = null
    private val path = Path()

    init {
        // Enable hardware acceleration for this view
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setTargetRect(rect: RectF) {
        targetRect = rect

        // Make sure to call invalidate to trigger a redraw
        invalidate()

        // Add logging to confirm the method is being called
        Log.d("TutorialOverlayView", "Updated target rect to: $rect")
    }

    fun getTargetRect(): RectF? = targetRect

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawCount++
        Log.d("TutorialOverlayView", "onDraw #$drawCount, targetRect: $targetRect")

        if (targetRect == null) {
            Log.w("TutorialOverlayView", "No target rect set yet!")
            return
        }

        // Check if the rect has changed
        if (lastTargetRect != targetRect) {
            Log.d("TutorialOverlayView", "Rect changed from $lastTargetRect to $targetRect")
            lastTargetRect = RectF(targetRect!!)
        }

        // Make sure we have a hardware layer for proper blending
        if (layerType != LAYER_TYPE_HARDWARE) {
            Log.d("TutorialOverlayView", "Setting hardware layer type")
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        // Save canvas layer for proper compositing
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // Draw dark overlay over entire view
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

        // Cut out the highlighted area with rounded corners
        targetRect?.let { rect ->
            Log.d("TutorialOverlayView", "Drawing cutout at: ${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom}")
            val cornerRadius = 20f // Rounded corners radius
            path.reset()
            path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(path, transparentPaint)
        }

        // Restore canvas
        canvas.restoreToCount(count)
    }
}