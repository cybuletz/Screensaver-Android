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

    private var targetRect: RectF? = null
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Save canvas layer for proper compositing
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

        // Draw dark overlay over entire view
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)

        // Cut out the highlighted area with rounded corners
        targetRect?.let { rect ->
            val cornerRadius = 20f // Rounded corners radius
            path.reset()
            path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.drawPath(path, transparentPaint)
        }

        // Restore canvas
        canvas.restoreToCount(count)
    }

    fun setTargetRect(rect: RectF) {
        targetRect = rect
        invalidate()
    }

    fun getTargetRect(): RectF? = targetRect
}