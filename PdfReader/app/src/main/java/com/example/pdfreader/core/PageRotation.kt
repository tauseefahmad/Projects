package com.example.pdfreader.core

/**
 * Maps between a page's own coordinates (PDF points, top-left origin, [width]×[height]) and
 * the coordinates of the page as displayed after rotating it clockwise by [degrees]
 * (0, 90, 180 or 270).
 */
class PageRotation(val width: Float, val height: Float, degrees: Int) {

    val degrees: Int = normalize(degrees)

    private val swapped get() = degrees == 90 || degrees == 270

    /** Size of the page as displayed. */
    val displayWidth: Float get() = if (swapped) height else width
    val displayHeight: Float get() = if (swapped) width else height

    fun toDisplay(x: Float, y: Float): Pair<Float, Float> = when (degrees) {
        90 -> (height - y) to x
        180 -> (width - x) to (height - y)
        270 -> y to (width - x)
        else -> x to y
    }

    fun fromDisplay(x: Float, y: Float): Pair<Float, Float> = when (degrees) {
        90 -> y to (height - x)
        180 -> (width - x) to (height - y)
        270 -> (width - y) to x
        else -> x to y
    }

    /** A rectangle (left, top, right, bottom) in page coordinates, as displayed. */
    fun rectToDisplay(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val (x1, y1) = toDisplay(left, top)
        val (x2, y2) = toDisplay(right, bottom)
        return floatArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }

    companion object {
        fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360 / 90 * 90
    }
}
