package com.example.pdfreader.core

/** Search options from the search bar. */
data class SearchOptions(val matchCase: Boolean = false, val wholeWord: Boolean = false)

/** One hit: characters [start, end) of [page]'s extracted text. */
data class SearchMatch(val page: Int, val start: Int, val end: Int)

/** A hit with surrounding text, for the results list. [matchStart]/[matchEnd] index into [text]. */
data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

object TextSearch {

    /** All non-overlapping occurrences of [query] in [text], as character ranges. */
    fun find(text: String, query: String, options: SearchOptions): List<IntRange> {
        val needle = query.trim()
        if (needle.isEmpty() || text.isEmpty()) return emptyList()
        val results = mutableListOf<IntRange>()
        var from = 0
        while (from <= text.length - needle.length) {
            val index = text.indexOf(needle, from, ignoreCase = !options.matchCase)
            if (index < 0) break
            val end = index + needle.length
            if (!options.wholeWord || (isBoundary(text, index - 1) && isBoundary(text, end))) {
                results += index until end
                from = end
            } else {
                from = index + 1
            }
        }
        return results
    }

    private fun isBoundary(text: String, index: Int): Boolean =
        index < 0 || index >= text.length || !text[index].isLetterOrDigit()

    /** Text around a match on one line, about [radius] characters each side, trimmed at word breaks. */
    fun snippet(text: String, start: Int, end: Int, radius: Int = 40): Snippet {
        var from = (start - radius).coerceAtLeast(0)
        var to = (end + radius).coerceAtMost(text.length)
        if (from > 0) {
            val space = text.indexOf(' ', from)
            if (space in from until start) from = space + 1
        }
        if (to < text.length) {
            val space = text.lastIndexOf(' ', to)
            if (space >= end) to = space
        }
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        val raw = text.substring(from, to)
        // Keep a one-line snippet: line breaks become spaces (same length, so indices stay valid).
        val body = raw.replace('\n', ' ').replace('\r', ' ')
        return Snippet(prefix + body + suffix, prefix.length + (start - from), prefix.length + (end - from))
    }

    /** Index of the first match on or after [page] (wrapping around), for "find from here". */
    fun firstFromPage(matches: List<SearchMatch>, page: Int): Int {
        if (matches.isEmpty()) return -1
        val index = matches.indexOfFirst { it.page >= page }
        return if (index >= 0) index else 0
    }
}
