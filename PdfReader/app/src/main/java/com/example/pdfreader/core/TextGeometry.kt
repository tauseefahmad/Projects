package com.example.pdfreader.core

import kotlin.math.max

/**
 * Hit-testing for text selection. Character boxes are 4 floats each (left, top, right, bottom)
 * in page points; separators added during extraction have a NaN left edge.
 */
object TextGeometry {

    /**
     * The character at (x, y), or the nearest one — preferring characters on the same line.
     * Returns -1 if the page has no positioned characters.
     */
    fun indexAt(boxes: FloatArray, x: Float, y: Float): Int {
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (i in 0 until boxes.size / 4) {
            val left = boxes[i * 4]
            if (left.isNaN()) continue
            val top = boxes[i * 4 + 1]
            val right = boxes[i * 4 + 2]
            val bottom = boxes[i * 4 + 3]
            val dy = max(0f, max(top - y, y - bottom))
            val dx = max(0f, max(left - x, x - right))
            // Being on the right line matters far more than horizontal distance.
            val score = dy * 100 + dx
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** The word containing [index]: letters and digits (plus ' and -) around it. */
    fun wordAt(text: String, index: Int): IntRange {
        if (index !in text.indices) return IntRange.EMPTY
        if (!isWordChar(text[index])) return index..index
        var start = index
        var end = index
        while (start > 0 && isWordChar(text[start - 1])) start--
        while (end < text.lastIndex && isWordChar(text[end + 1])) end++
        // Don't keep apostrophes/hyphens dangling at the edges ("word-" or "'quote").
        while (start < end && !text[start].isLetterOrDigit()) start++
        while (end > start && !text[end].isLetterOrDigit()) end--
        return start..end
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    /** Orders two character indices into a half-open range [start, end) covering both. */
    fun rangeBetween(anchor: Int, focus: Int): IntRange =
        if (anchor <= focus) anchor..focus else focus..anchor
}
