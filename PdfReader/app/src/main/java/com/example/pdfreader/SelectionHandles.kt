package com.example.pdfreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot

/**
 * The two drag handles of a text selection, drawn over the pages. Touches away from the
 * handles fall through to the page list.
 */
class SelectionHandles @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Bottom-left of the first selected character, in this view's coordinates. */
    var start: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Bottom-right of the last selected character. */
    var end: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    /** A handle was dragged so its anchor point is now at (x, y). */
    var onDrag: ((isStart: Boolean, x: Float, y: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val radius = 11 * resources.displayMetrics.density
    private val touchRadius = 28 * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
    }

    private var dragging: Boolean? = null // true = start handle, false = end handle
    private var grabDx = 0f
    private var grabDy = 0f

    /** Centre of a handle's circle: below its anchor, to the left for start and right for end. */
    private fun center(anchor: PointF, isStart: Boolean) =
        PointF(if (isStart) anchor.x - radius else anchor.x + radius, anchor.y + radius)

    override fun onDraw(canvas: Canvas) {
        start?.let { drawHandle(canvas, it, isStart = true) }
        end?.let { drawHandle(canvas, it, isStart = false) }
    }

    private fun drawHandle(canvas: Canvas, anchor: PointF, isStart: Boolean) {
        val c = center(anchor, isStart)
        canvas.drawCircle(c.x, c.y, radius, paint)
        // A square corner pointing at the anchor makes the classic teardrop shape.
        if (isStart) {
            canvas.drawRect(c.x, anchor.y, anchor.x, c.y, paint)
        } else {
            canvas.drawRect(anchor.x, anchor.y, c.x, c.y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = listOfNotNull(start?.let { true to it }, end?.let { false to it })
                    .map { (isStart, anchor) -> Triple(isStart, anchor, center(anchor, isStart)) }
                    .filter { (_, _, c) -> hypot(event.x - c.x, event.y - c.y) <= touchRadius }
                    .minByOrNull { (_, _, c) -> hypot(event.x - c.x, event.y - c.y) }
                    ?: return false
                dragging = hit.first
                grabDx = hit.second.x - event.x
                grabDy = hit.second.y - event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { isStart ->
                // Aim slightly above the anchor so the finger doesn't cover the text being picked.
                onDrag?.invoke(isStart, event.x + grabDx, event.y + grabDy - radius)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != null) onDragEnd?.invoke()
                dragging = null
            }
        }
        return true
    }
}
