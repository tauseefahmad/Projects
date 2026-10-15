package com.example.pdfreader.core

import kotlin.math.roundToInt

/** How pages flow through the reader. */
enum class ViewMode { CONTINUOUS, SINGLE_PAGE }

/** Whether pages are shown alone or as book-style pairs. */
enum class SpreadMode { NONE, TWO_PAGE, TWO_PAGE_COVER }

data class PageBox(val width: Int, val height: Int)

/**
 * Groups document pages into list items. Each item holds one page, or two pages side by side
 * in spread mode. With [SpreadMode.TWO_PAGE_COVER] the first page stands alone like a book cover.
 */
class Spreads(val pageCount: Int, val mode: SpreadMode) {

    val items: List<IntRange> = buildList {
        var page = 0
        if (mode == SpreadMode.TWO_PAGE_COVER && pageCount > 0) {
            add(0..0)
            page = 1
        }
        val perItem = if (mode == SpreadMode.NONE) 1 else 2
        while (page < pageCount) {
            val last = minOf(page + perItem, pageCount) - 1
            add(page..last)
            page = last + 1
        }
    }

    val size: Int get() = items.size

    /** Index of the item that contains [page]. */
    fun itemForPage(page: Int): Int {
        if (items.isEmpty()) return 0
        val clamped = page.coerceIn(0, pageCount - 1)
        var low = 0
        var high = items.lastIndex
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (items[mid].first <= clamped) low = mid else high = mid - 1
        }
        return low
    }
}

object PageFit {

    /**
     * Sizes the pages of one item so they share a height and together fill [availableWidth]
     * (minus [gap] between pages). When [availableHeight] is given (single-page mode), the
     * pages are also shrunk to fit it.
     *
     * @param pages page sizes in PDF points, as (width, height).
     */
    fun fit(pages: List<PageBox>, availableWidth: Int, availableHeight: Int? = null, gap: Int = 0): List<PageBox> {
        if (pages.isEmpty() || availableWidth <= 0) return pages.map { PageBox(0, 0) }
        val aspectSum = pages.sumOf { it.width.toDouble() / it.height }
        val widthForPages = (availableWidth - gap * (pages.size - 1)).coerceAtLeast(1)
        var height = widthForPages / aspectSum
        if (availableHeight != null && availableHeight > 0) height = minOf(height, availableHeight.toDouble())
        return pages.map { PageBox((height * it.width / it.height).roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)) }
    }
}

object Navigation {

    /** Maps a fast-scroll handle position (0..1) to a page index. */
    fun pageForFraction(fraction: Float, pageCount: Int): Int =
        if (pageCount <= 1) 0 else (fraction.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt()

    /** Inverse of [pageForFraction]. */
    fun fractionForPage(page: Int, pageCount: Int): Float =
        if (pageCount <= 1) 0f else page.coerceIn(0, pageCount - 1).toFloat() / (pageCount - 1)

    /** Parses what the user typed in "Go to page" (1-based) into a page index, or null if invalid. */
    fun parsePageInput(text: String, pageCount: Int): Int? {
        val number = text.trim().toIntOrNull() ?: return null
        if (pageCount <= 0) return null
        return (number - 1).coerceIn(0, pageCount - 1)
    }
}
