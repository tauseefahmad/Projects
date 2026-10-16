package com.example.pdfreader.core

/**
 * Coordinate conversions for OCR. A page is rendered as shown (turned by its /Rotate), the
 * picture may be turned again so the text stands upright for recognition, and recognized words
 * must be placed back on the unrotated page.
 */
object OcrGeometry {

    /**
     * A pixel in a picture turned clockwise by [turn] degrees (0, 90, 180, 270) back in the
     * original picture of [width] × [height] pixels.
     */
    fun unturn(u: Float, v: Float, turn: Int, width: Float, height: Float): Pt = when (normalize(turn)) {
        90 -> Pt(v, height - u)
        180 -> Pt(width - u, height - v)
        270 -> Pt(width - v, u)
        else -> Pt(u, v)
    }

    /**
     * A point on the page as displayed (turned clockwise by the page's [rotation]), in points
     * from the top-left, on the unrotated page of [width] × [height] points (top-left origin).
     */
    fun displayToPage(u: Float, v: Float, rotation: Int, width: Float, height: Float): Pt = when (normalize(rotation)) {
        90 -> Pt(v, height - u)
        180 -> Pt(width - u, height - v)
        270 -> Pt(width - v, u)
        else -> Pt(u, v)
    }

    /** Size of the page as displayed. */
    fun displaySize(rotation: Int, width: Float, height: Float): Pair<Float, Float> =
        if (normalize(rotation) % 180 == 90) height to width else width to height

    /**
     * Direction of the text on the unrotated page, in degrees counter-clockwise (PDF
     * convention): text made upright by turning the picture [turn]° clockwise, on a page
     * displayed with [rotation].
     */
    fun textAngle(turn: Int, rotation: Int): Int = normalize(turn + rotation)

    fun normalize(degrees: Int) = ((degrees % 360) + 360) % 360
}
