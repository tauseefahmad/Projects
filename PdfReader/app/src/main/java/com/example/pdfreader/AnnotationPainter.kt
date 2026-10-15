package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.example.pdfreader.core.Annotation
import com.example.pdfreader.core.Box
import com.example.pdfreader.core.Geometry
import com.example.pdfreader.core.MarkupKind
import com.example.pdfreader.core.ShapeKind

/**
 * Draws [Annotation]s on a canvas that is already mapped to page coordinates (PDF points,
 * top-left origin). It mirrors the appearance streams written by [AnnotationIO], so what you
 * see while editing is what other PDF apps show after saving.
 */
object AnnotationPainter {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val images = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Highlights are drawn translucent so the text stays readable (PDFs use multiply). */
    private const val HIGHLIGHT_ALPHA = 0.4f

    fun draw(canvas: Canvas, a: Annotation) {
        when (a) {
            is Annotation.Markup -> drawMarkup(canvas, a)
            is Annotation.Note -> drawNote(canvas, a)
            is Annotation.FreeText -> drawFreeText(canvas, a)
            is Annotation.Ink -> drawInk(canvas, a)
            is Annotation.Shape -> drawShape(canvas, a)
            is Annotation.Stamp -> drawStamp(canvas, a)
            is Annotation.Image -> drawImage(canvas, a)
        }
    }

    private fun withAlpha(color: Int, alpha: Float) = Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun drawMarkup(canvas: Canvas, a: Annotation.Markup) {
        when (a.kind) {
            MarkupKind.HIGHLIGHT -> {
                fill.color = withAlpha(a.color, HIGHLIGHT_ALPHA)
                for (line in a.lines) canvas.drawRect(line.left, line.top, line.right, line.bottom, fill)
            }
            MarkupKind.UNDERLINE, MarkupKind.STRIKEOUT -> {
                stroke.color = a.color
                stroke.strokeCap = Paint.Cap.BUTT
                for (line in a.lines) {
                    val y = if (a.kind == MarkupKind.UNDERLINE) line.bottom - line.height * 0.08f else line.top + line.height * 0.55f
                    stroke.strokeWidth = (line.height / 14).coerceIn(0.75f, 3f)
                    canvas.drawLine(line.left, y, line.right, y, stroke)
                }
                stroke.strokeCap = Paint.Cap.ROUND
            }
            MarkupKind.SQUIGGLY -> {
                stroke.color = a.color
                stroke.strokeWidth = 0.8f
                for (line in a.lines) {
                    val amplitude = (line.height / 10).coerceIn(1f, 3f)
                    val step = amplitude * 2
                    path.reset()
                    var x = line.left
                    var up = true
                    path.moveTo(x, line.bottom)
                    while (x < line.right) {
                        x = minOf(x + step, line.right)
                        path.lineTo(x, line.bottom - if (up) amplitude else 0f)
                        up = !up
                    }
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }

    private fun drawNote(canvas: Canvas, a: Annotation.Note) {
        val b = a.bounds
        fill.color = a.color
        canvas.drawRect(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1, fill)
        stroke.color = Color.rgb(77, 77, 77)
        stroke.strokeWidth = 0.6f
        canvas.drawRect(b.left + 1, b.top + 1, b.right - 1, b.bottom - 1, stroke)
        stroke.color = Color.rgb(64, 64, 64)
        for (i in 1..3) {
            val y = b.top + 1 + i * (b.height - 2) / 4
            canvas.drawLine(b.left + 4, y, b.right - 4, y, stroke)
        }
    }

    fun textLayout(text: String, fontSize: Float, color: Int, width: Float): StaticLayout {
        this.text.textSize = fontSize
        this.text.color = color
        this.text.typeface = Typeface.SANS_SERIF
        return StaticLayout.Builder.obtain(text, 0, text.length, this.text, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()
    }

    private fun drawFreeText(canvas: Canvas, a: Annotation.FreeText) {
        val layout = textLayout(a.text, a.fontSize, a.color, a.box.width - 4)
        canvas.save()
        canvas.clipRect(a.box.left, a.box.top, a.box.right, a.box.bottom)
        canvas.translate(a.box.left + 2, a.box.top + a.fontSize * 0.1f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawInk(canvas: Canvas, a: Annotation.Ink) {
        stroke.color = withAlpha(a.color, a.opacity)
        stroke.strokeWidth = a.width
        for (s in a.strokes) {
            if (s.isEmpty()) continue
            path.reset()
            path.moveTo(s[0].x, s[0].y)
            if (s.size == 1) path.lineTo(s[0].x + 0.01f, s[0].y)
            for (p in s.drop(1)) path.lineTo(p.x, p.y)
            canvas.drawPath(path, stroke)
        }
    }

    private fun drawShape(canvas: Canvas, a: Annotation.Shape) {
        stroke.color = withAlpha(a.color, a.opacity)
        stroke.strokeWidth = a.width
        val p = a.points
        if (p.isEmpty()) return
        a.fill?.let { fill.color = withAlpha(it, a.opacity) }
        when (a.kind) {
            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> {
                val b = Box.around(p)
                rect.set(b.left, b.top, b.right, b.bottom)
                if (a.kind == ShapeKind.RECTANGLE) {
                    if (a.fill != null) canvas.drawRect(rect, fill)
                    canvas.drawRect(rect, stroke)
                } else {
                    if (a.fill != null) canvas.drawOval(rect, fill)
                    canvas.drawOval(rect, stroke)
                }
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                if (p.size < 2) return
                canvas.drawLine(p[0].x, p[0].y, p[1].x, p[1].y, stroke)
                if (a.kind == ShapeKind.ARROW) {
                    val (w1, w2) = Geometry.arrowHead(p[0], p[1], a.width * 4 + 6f)
                    path.reset()
                    path.moveTo(w1.x, w1.y)
                    path.lineTo(p[1].x, p[1].y)
                    path.lineTo(w2.x, w2.y)
                    canvas.drawPath(path, stroke)
                }
            }
            ShapeKind.POLYGON -> {
                path.reset()
                path.moveTo(p[0].x, p[0].y)
                for (q in p.drop(1)) path.lineTo(q.x, q.y)
                path.close()
                if (a.fill != null) canvas.drawPath(path, fill)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawStamp(canvas: Canvas, a: Annotation.Stamp) {
        val b = a.box
        val inset = 2f
        stroke.color = a.color
        stroke.strokeWidth = 2.5f
        stroke.strokeJoin = Paint.Join.MITER
        canvas.drawRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, stroke)
        stroke.strokeJoin = Paint.Join.ROUND
        val label = a.label.ifBlank { a.kind.label }
        val hasSubtitle = a.subtitle.isNotBlank()
        text.color = a.color
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        var size = b.height * (if (hasSubtitle) 0.45f else 0.6f)
        text.textSize = size
        val width = text.measureText(label)
        if (width > b.width - 12) size *= (b.width - 12) / width
        text.textSize = size
        val labelWidth = text.measureText(label)
        // Same baselines as the PDF appearance (which measures from the bottom).
        val baseline = if (hasSubtitle) b.top + inset + 4 + size * 0.8f else b.bottom - (b.height - size * 0.7f) / 2
        canvas.drawText(label, b.left + (b.width - labelWidth) / 2, baseline, text)
        if (hasSubtitle) {
            text.typeface = Typeface.SANS_SERIF
            text.textSize = (b.height * 0.2f).coerceAtMost(10f)
            val subWidth = text.measureText(a.subtitle)
            canvas.drawText(a.subtitle, b.left + (b.width - subWidth) / 2, b.bottom - inset - 4, text)
        }
    }

    private fun drawImage(canvas: Canvas, a: Annotation.Image) {
        val bitmap = image(a.imagePath)
        rect.set(a.box.left, a.box.top, a.box.right, a.box.bottom)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        } else {
            stroke.color = Color.GRAY
            stroke.strokeWidth = 1f
            canvas.drawRect(rect, stroke)
        }
    }

    /** A decoded picture, shrunk to at most about 1500 px on its long side. */
    fun image(path: String): Bitmap? {
        images.get(path)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1500) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        images.put(path, bitmap)
        return bitmap
    }
}
