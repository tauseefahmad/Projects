package com.example.pdfreader.core

import kotlin.math.cos
import kotlin.math.sin

/** Where a header/footer/page number goes on the page. */
enum class StampPosition(val top: Boolean, val align: Int) {
    TOP_LEFT(true, -1), TOP_CENTER(true, 0), TOP_RIGHT(true, 1),
    BOTTOM_LEFT(false, -1), BOTTOM_CENTER(false, 0), BOTTOM_RIGHT(false, 1),
}

/** Geometry and text helpers for page tools; PDF coordinates have their origin bottom-left. */
object StampMath {

    const val POINTS_PER_MM = 72f / 25.4f

    fun mmToPoints(mm: Float) = mm * POINTS_PER_MM

    /**
     * Fills a stamp template: {n} page number (counting from [startNumber]), {total} page count,
     * {date} and {file}. Example: "Page {n} of {total}".
     */
    fun fill(template: String, pageIndex: Int, pageCount: Int, startNumber: Int = 1, date: String = "", file: String = ""): String =
        template.replace("{n}", (startNumber + pageIndex).toString())
            .replace("{total}", (startNumber + pageCount - 1).toString())
            .replace("{date}", date)
            .replace("{file}", file)

    /** Baseline start (x, y) for a line of text [textWidth] wide at [position], [margin] from the edges. */
    fun place(position: StampPosition, pageWidth: Float, pageHeight: Float, textWidth: Float, fontSize: Float, margin: Float): Pair<Float, Float> {
        val x = when (position.align) {
            -1 -> margin
            0 -> (pageWidth - textWidth) / 2
            else -> pageWidth - margin - textWidth
        }
        val y = if (position.top) pageHeight - margin - fontSize else margin
        return x to y
    }

    /**
     * Baseline start for text [textWidth]×[textHeight] rotated by [angleDegrees] (counter-clockwise)
     * so that its centre sits at the page centre — for diagonal watermarks.
     */
    fun centeredRotated(pageWidth: Float, pageHeight: Float, textWidth: Float, textHeight: Float, angleDegrees: Float): Pair<Float, Float> {
        val a = Math.toRadians(angleDegrees.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        val halfW = textWidth / 2
        val halfH = textHeight / 2
        return (pageWidth / 2 - (c * halfW - s * halfH)) to (pageHeight / 2 - (s * halfW + c * halfH))
    }

    /** Crop box after trimming margins (points) from a page box; never smaller than 1 pt. */
    fun crop(left: Float, bottom: Float, right: Float, top: Float, trimLeft: Float, trimBottom: Float, trimRight: Float, trimTop: Float): FloatArray {
        val l = left + trimLeft
        val b = bottom + trimBottom
        val r = maxOf(l + 1f, right - trimRight)
        val t = maxOf(b + 1f, top - trimTop)
        return floatArrayOf(l, b, r, t)
    }

    /** Scale and offset to fit a [width]×[height] page centred on a [targetWidth]×[targetHeight] sheet. */
    fun fit(width: Float, height: Float, targetWidth: Float, targetHeight: Float): Triple<Float, Float, Float> {
        val scale = minOf(targetWidth / width, targetHeight / height)
        return Triple(scale, (targetWidth - width * scale) / 2, (targetHeight - height * scale) / 2)
    }
}
