package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.appcompat.widget.AppCompatImageView
import android.graphics.DashPathEffect
import android.graphics.Matrix
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.AnnotationTransform
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

    /** What [annotationLayer] provides: annotations to draw over the page. */
    interface AnnotationLayer {
        fun annotationsOn(page: Int): List<Annotation>
        /** An annotation being drawn or dragged, shown on top. */
        val preview: Annotation?
        /** The annotation selected for editing, outlined with a resize handle. */
        val selected: Annotation?
    }

    var annotationLayer: AnnotationLayer? = null

    /** Radius of the resize handle drawn on the selected annotation, in view pixels. */
    val handleRadius: Float = 11 * resources.displayMetrics.density

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
    private val pageMatrix = Matrix()
    private val src = FloatArray(6)
    private val dst = FloatArray(6)
    private val frameStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(33, 150, 243)
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6 * resources.displayMetrics.density, 4 * resources.displayMetrics.density), 0f)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(33, 150, 243) }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2 * resources.displayMetrics.density
    }

    /** Maps page coordinates to this view, including the display rotation. */
    private fun pageToLocal(): Matrix {
        val r = pageRotation ?: return pageMatrix.apply { reset() }
        val points = listOf(0f to 0f, r.width to 0f, 0f to r.height)
        points.forEachIndexed { i, (x, y) ->
            src[i * 2] = x
            src[i * 2 + 1] = y
            val local = toLocal(x, y)
            dst[i * 2] = local.x
            dst[i * 2 + 1] = local.y
        }
        pageMatrix.setPolyToPoly(src, 0, dst, 0, 3)
        return pageMatrix
    }

    /** The selected annotation's frame in view pixels (padded), or null. */
    fun selectionFrame(a: Annotation): RectF {
        val b = a.bounds
        val frame = toLocalRect(RectF(b.left, b.top, b.right, b.bottom))
        frame.inset(-4 * resources.displayMetrics.density, -4 * resources.displayMetrics.density)
        return frame
    }

    private fun drawAnnotations(canvas: Canvas) {
        val layer = annotationLayer ?: return
        val items = layer.annotationsOn(page)
        val preview = layer.preview?.takeIf { it.page == page }
        if (items.isNotEmpty() || preview != null) {
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            canvas.concat(pageToLocal())
            for (a in items) AnnotationPainter.draw(canvas, a)
            preview?.let { AnnotationPainter.draw(canvas, it) }
            canvas.restore()
        }
        val selected = layer.selected?.takeIf { it.page == page } ?: return
        val frame = selectionFrame(preview?.takeIf { it.id == selected.id } ?: selected)
        canvas.drawRect(frame, frameStroke)
        if (AnnotationTransform.canResize(selected)) {
            canvas.drawCircle(frame.right, frame.bottom, handleRadius, handleFill)
            canvas.drawCircle(frame.right, frame.bottom, handleRadius, handleStroke)
        }
    }

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
        drawAnnotations(canvas)
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
