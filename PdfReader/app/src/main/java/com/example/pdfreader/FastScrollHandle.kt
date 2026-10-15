package com.example.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import com.example.pdfreader.core.Navigation

/**
 * A draggable thumb on the right edge for jumping through long documents. While dragging it
 * shows a bubble with the target page. Touches away from the thumb fall through to the list.
 */
class FastScrollHandle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var pageCount = 0
        set(value) {
            field = value
            invalidate()
        }

    /** Position of the thumb, 0 (first page) to 1 (last page). Ignored while dragging. */
    var fraction: Float
        get() = position
        set(value) {
            if (dragging) return
            position = value.coerceIn(0f, 1f)
            invalidate()
        }
    private var position = 0f

    var onScrubbed: ((page: Int) -> Unit)? = null

    var dragging = false
        private set

    private val density = resources.displayMetrics.density
    private val thumbWidth = 6 * density
    private val thumbHeight = 48 * density
    private val thumbMargin = 10 * density
    private val touchWidth = 44 * density

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.DKGRAY)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = thumbPaint.color }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16 * density
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private val track get() = (height - paddingTop - paddingBottom - thumbHeight).coerceAtLeast(1f)
    private val thumbTop get() = paddingTop + position * track

    override fun onDraw(canvas: Canvas) {
        if (pageCount < 2) return
        val right = width - thumbMargin
        rect.set(right - thumbWidth, thumbTop, right, thumbTop + thumbHeight)
        canvas.drawRoundRect(rect, thumbWidth, thumbWidth, thumbPaint)

        if (dragging) {
            val label = (Navigation.pageForFraction(position, pageCount) + 1).toString()
            val bubbleWidth = textPaint.measureText(label) + 32 * density
            val centerY = thumbTop + thumbHeight / 2
            rect.set(right - thumbWidth - 16 * density - bubbleWidth, centerY - 22 * density, right - thumbWidth - 16 * density, centerY + 22 * density)
            canvas.drawRoundRect(rect, 22 * density, 22 * density, bubblePaint)
            canvas.drawText(label, rect.centerX(), centerY - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val onThumb = event.x >= width - touchWidth &&
                    event.y >= thumbTop - thumbHeight / 2 && event.y <= thumbTop + thumbHeight * 1.5f
                if (pageCount < 2 || !onThumb) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubTo(event.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) scrubTo(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // The finger can lift beyond the last move event; land where it was released.
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) scrubTo(event.y)
                dragging = false
                invalidate()
            }
        }
        return true
    }

    private fun scrubTo(y: Float) {
        position = ((y - paddingTop - thumbHeight / 2) / track).coerceIn(0f, 1f)
        onScrubbed?.invoke(Navigation.pageForFraction(position, pageCount))
        invalidate()
    }
}
