package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.appcompat.widget.AppCompatImageView

/**
 * One rendered page. On top of the page bitmap it draws, in order: a sharp [detail] render of
 * the visible area when zoomed in, then search [highlights] (given in PDF points, top-left origin).
 */
class PageView(context: Context) : AppCompatImageView(context) {

    /** The page this view shows, or -1 while unbound. */
    var page: Int = -1

    /** Width of the page in PDF points, to map highlight coordinates to the view. */
    var pageWidthPoints: Float = 0f

    private var detail: Bitmap? = null
    private val detailRect = RectF()
    private val detailPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    var highlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var currentHighlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val highlightPaint = Paint().apply { color = Color.argb(90, 255, 213, 0) }
    private val currentPaint = Paint().apply { color = Color.argb(130, 255, 120, 0) }
    private val scratch = RectF()

    /** Sets a high-resolution render covering [rect] (view coordinates), or clears it with null. */
    fun setDetail(bitmap: Bitmap?, rect: RectF? = null) {
        detail = bitmap
        if (rect != null) detailRect.set(rect)
        invalidate()
    }

    val hasDetail: Boolean get() = detail != null

    fun clearOverlays() {
        detail = null
        highlights = emptyList()
        currentHighlights = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detail?.let {
            // Same filter as the page bitmap (e.g. inverted for dark pages).
            detailPaint.colorFilter = colorFilter
            canvas.drawBitmap(it, null, detailRect, detailPaint)
        }
        if (pageWidthPoints <= 0f || (highlights.isEmpty() && currentHighlights.isEmpty())) return
        val scale = width / pageWidthPoints
        for (rect in highlights) drawScaled(canvas, rect, scale, highlightPaint)
        for (rect in currentHighlights) drawScaled(canvas, rect, scale, currentPaint)
    }

    private fun drawScaled(canvas: Canvas, rect: RectF, scale: Float, paint: Paint) {
        scratch.set(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
        // A little padding so highlights don't clip glyph edges.
        scratch.inset(-scale, -scale)
        canvas.drawRect(scratch, paint)
    }
}
