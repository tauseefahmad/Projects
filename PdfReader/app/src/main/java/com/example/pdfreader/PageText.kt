package com.example.pdfreader

import android.graphics.RectF
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs

/**
 * A page's extracted text with each character's box in PDF points (top-left origin, relative
 * to the crop box, as PdfRenderer draws it). Separators added by extraction have no box.
 */
class PageText(val text: String, private val boxes: FloatArray) {

    /** Boxes covering characters [start, end), merged into one rectangle per line. */
    fun boxesFor(start: Int, end: Int): List<RectF> {
        val result = mutableListOf<RectF>()
        var current: RectF? = null
        for (i in start.coerceAtLeast(0) until end.coerceAtMost(text.length)) {
            val left = boxes[i * 4]
            if (left.isNaN()) continue
            val top = boxes[i * 4 + 1]
            val right = boxes[i * 4 + 2]
            val bottom = boxes[i * 4 + 3]
            val line = current
            if (line != null && abs(line.bottom - bottom) < (bottom - top) / 2 && left >= line.left - 1) {
                line.union(left, top, right, bottom)
            } else {
                current = RectF(left, top, right, bottom).also { result += it }
            }
        }
        return result
    }

    companion object {
        val EMPTY = PageText("", FloatArray(0))
    }
}

/** Collects text and character positions for one page. */
internal class PositionStripper : PDFTextStripper() {

    private val text = StringBuilder()
    private var boxes = FloatArray(1024)

    init {
        setSortByPosition(true)
    }

    private fun add(char: Char, left: Float, top: Float, right: Float, bottom: Float) {
        val index = text.length
        if ((index + 1) * 4 > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        text.append(char)
        boxes[index * 4] = left
        boxes[index * 4 + 1] = top
        boxes[index * 4 + 2] = right
        boxes[index * 4 + 3] = bottom
    }

    override fun writeString(string: String, textPositions: List<TextPosition>) {
        for (position in textPositions) {
            val unicode = position.unicode ?: continue
            val left = position.xDirAdj
            val bottom = position.yDirAdj
            val top = bottom - position.heightDir.coerceAtLeast(1f)
            val right = left + position.widthDirAdj
            for (char in unicode) add(char, left, top, right, bottom)
        }
    }

    override fun writeWordSeparator() = add(' ', Float.NaN, 0f, 0f, 0f)

    override fun writeLineSeparator() = add('\n', Float.NaN, 0f, 0f, 0f)

    fun result() = PageText(text.toString(), boxes.copyOf(text.length * 4))
}
