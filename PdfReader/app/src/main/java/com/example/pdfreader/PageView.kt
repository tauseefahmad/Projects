package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.appcompat.widget.AppCompatImageView
import com.example.pdfreader.core.PageRotation

/**
 * One rendered page. On top of the page bitmap it draws, in order: a sharp [detail] render of
 * the visible area when zoomed in, then search [highlights] (given in PDF points, top-left origin).
 */
class PageView(context: Context) : AppCompatImageView(context) {

    /** The page this view shows, or -1 while unbound. */
    var page: Int = -1

    /** Page size in PDF points and how it is turned, to map page coordinates to the view. */
    var pageRotation: PageRotation? = null

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

    /** Selected text, in PDF points. */
    var selection: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** View pixels per PDF point. */
    val scale: Float get() = pageRotation?.let { width / it.displayWidth } ?: 1f

    /** A point in this view (pixels) as page coordinates (PDF points, unrotated). */
    fun toPagePoint(localX: Float, localY: Float): PointF {
        val rotation = pageRotation ?: return PointF(localX, localY)
        val (x, y) = rotation.fromDisplay(localX / scale, localY / scale)
        return PointF(x, y)
    }

    /** Page coordinates (PDF points) as a point in this view (pixels). */
    fun toLocal(pageX: Float, pageY: Float): PointF {
        val rotation = pageRotation ?: return PointF(pageX, pageY)
        val (x, y) = rotation.toDisplay(pageX, pageY)
        return PointF(x * scale, y * scale)
    }

    /** A page-coordinate rectangle as a rectangle in this view (pixels). */
    fun toLocalRect(rect: RectF): RectF {
        val rotation = pageRotation ?: return RectF(rect)
        val r = rotation.rectToDisplay(rect.left, rect.top, rect.right, rect.bottom)
        val s = scale
        return RectF(r[0] * s, r[1] * s, r[2] * s, r[3] * s)
    }

    private val selectionPaint = Paint().apply { color = Color.argb(80, 33, 150, 243) }
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
        selection = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detail?.let {
            // Same filter as the page bitmap (e.g. inverted for dark pages).
            detailPaint.colorFilter = colorFilter
            canvas.drawBitmap(it, null, detailRect, detailPaint)
        }
        if (pageRotation == null) return
        for (rect in highlights) drawMapped(canvas, rect, highlightPaint)
        for (rect in currentHighlights) drawMapped(canvas, rect, currentPaint)
        for (rect in selection) drawMapped(canvas, rect, selectionPaint)
    }

    private fun drawMapped(canvas: Canvas, rect: RectF, paint: Paint) {
        scratch.set(toLocalRect(rect))
        // A little padding so highlights don't clip glyph edges.
        scratch.inset(-scale, -scale)
        canvas.drawRect(scratch, paint)
    }
}
