package com.example.pdfreader.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Formatting {

    /** "512 B", "14.2 KB", "3.4 MB", "1.1 GB". */
    fun bytes(size: Long): String {
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
    }

    private data class PaperSize(val name: String, val widthMm: Int, val heightMm: Int, val imperial: Boolean = false)

    private val PAPER_SIZES = listOf(
        PaperSize("A3", 297, 420),
        PaperSize("A4", 210, 297),
        PaperSize("A5", 148, 210),
        PaperSize("A6", 105, 148),
        PaperSize("B5", 176, 250),
        PaperSize("Letter", 216, 279, imperial = true),
        PaperSize("Legal", 216, 356, imperial = true),
        PaperSize("Tabloid", 279, 432, imperial = true),
    )

    private fun pointsToMm(points: Float) = points / 72f * 25.4f

    /**
     * A page size from PDF points: "A4 (210 × 297 mm)", "Letter (8.5 × 11 in)", "A4 landscape
     * (297 × 210 mm)" or just "100 × 200 mm" for other sizes.
     */
    fun pageSize(widthPt: Float, heightPt: Float): String {
        val w = pointsToMm(widthPt)
        val h = pointsToMm(heightPt)
        val portrait = w <= h
        val short = minOf(w, h)
        val long = maxOf(w, h)
        val match = PAPER_SIZES.firstOrNull { abs(it.widthMm - short) <= 2 && abs(it.heightMm - long) <= 2 }
        val orientation = if (portrait) "" else " landscape"
        return when {
            match == null -> "${w.roundToInt()} × ${h.roundToInt()} mm"
            match.imperial -> {
                val inches = { mm: Float -> String.format(Locale.US, "%.1f", mm / 25.4f).removeSuffix(".0") }
                "${match.name}$orientation (${inches(w)} × ${inches(h)} in)"
            }
            else -> {
                val (a, b) = if (portrait) match.widthMm to match.heightMm else match.heightMm to match.widthMm
                "${match.name}$orientation ($a × $b mm)"
            }
        }
    }
}
