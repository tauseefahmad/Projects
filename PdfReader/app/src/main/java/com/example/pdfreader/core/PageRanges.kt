package com.example.pdfreader.core

/** Thrown for page-range text that can't be understood; [message] says what is wrong. */
class PageRangeException(message: String) : IllegalArgumentException(message)

object PageRanges {

    /**
     * Parses ranges like "1-5, 6-10, 12", "8-" (to the end) or "-3" (from the start) into
     * 0-based inclusive ranges, in the order written.
     * @throws PageRangeException for empty input, bad numbers or pages outside 1..[pageCount].
     */
    fun parse(text: String, pageCount: Int): List<IntRange> {
        val parts = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) throw PageRangeException("Enter pages, e.g. 1-5, 8")
        return parts.map { part ->
            val dash = part.replace('–', '-').replace('—', '-')
            val (from, to) = when {
                !dash.contains('-') -> number(dash, pageCount).let { it to it }
                dash.startsWith('-') -> 1 to number(dash.drop(1), pageCount)
                dash.endsWith('-') -> number(dash.dropLast(1), pageCount) to pageCount
                else -> {
                    val (a, b) = dash.split('-', limit = 2)
                    number(a, pageCount) to number(b, pageCount)
                }
            }
            if (from > to) throw PageRangeException("$part: the first page is after the last")
            (from - 1)..(to - 1)
        }
    }

    private fun number(text: String, pageCount: Int): Int {
        val value = text.trim().toIntOrNull() ?: throw PageRangeException("\"${text.trim()}\" is not a page number")
        if (value !in 1..pageCount) throw PageRangeException("Page $value doesn't exist (1–$pageCount)")
        return value
    }

    /** Chunks of [size] pages: every N pages. */
    fun everyN(pageCount: Int, size: Int): List<IntRange> {
        require(size > 0) { "size must be positive" }
        return (0 until pageCount step size).map { it..minOf(it + size - 1, pageCount - 1) }
    }

    /** Ranges starting at each of [starts] (0-based, e.g. bookmark pages) up to the next one. */
    fun fromStarts(pageCount: Int, starts: Collection<Int>): List<IntRange> {
        val sorted = (starts.filter { it in 0 until pageCount } + 0).toSortedSet().toList()
        return sorted.mapIndexed { i, start -> start..((sorted.getOrNull(i + 1) ?: pageCount) - 1) }
    }

    /** "1–3, 5" style text for 0-based page indices, merging consecutive pages. */
    fun describe(pages: Collection<Int>): String {
        val sorted = pages.toSortedSet().toList()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        for (page in sorted.drop(1) + Int.MIN_VALUE) {
            if (page == prev + 1) {
                prev = page
                continue
            }
            parts += if (start == prev) "${start + 1}" else "${start + 1}–${prev + 1}"
            start = page
            prev = page
        }
        return parts.joinToString(", ")
    }
}
