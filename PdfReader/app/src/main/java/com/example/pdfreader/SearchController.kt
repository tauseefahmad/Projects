package com.example.pdfreader

import com.example.pdfreader.core.SearchMatch
import com.example.pdfreader.core.SearchOptions
import com.example.pdfreader.core.TextSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a text search across the document in the background, page by page, reporting matches as
 * they are found. Page text is extracted once and cached by [PdfStructure], so later searches
 * of the same document are fast.
 */
class SearchController(private val scope: CoroutineScope, private val listener: Listener) {

    interface Listener {
        /** Matches, the current index or the searching state changed. */
        fun onSearchUpdated()

        /** The current match changed (by search, next/previous or selection); show it. */
        fun onCurrentMatchChanged(match: SearchMatch)
    }

    var structure: PdfStructure? = null
    var pageCount = 0

    var query = ""
        private set
    var options = SearchOptions()
        private set
    var matches: List<SearchMatch> = emptyList()
        private set
    var currentIndex = -1
        private set
    var searching = false
        private set
    var pagesSearched = 0
        private set

    val current: SearchMatch? get() = matches.getOrNull(currentIndex)

    private var job: Job? = null

    fun search(query: String, options: SearchOptions, fromPage: Int) {
        job?.cancel()
        this.query = query.trim()
        this.options = options
        matches = emptyList()
        currentIndex = -1
        pagesSearched = 0
        val structure = structure
        if (this.query.isEmpty() || structure == null) {
            searching = false
            listener.onSearchUpdated()
            return
        }
        searching = true
        listener.onSearchUpdated()
        val needle = this.query
        job = scope.launch {
            val found = mutableListOf<SearchMatch>()
            for (page in 0 until pageCount) {
                val text = try {
                    structure.pageText(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ""
                }
                val hits = TextSearch.find(text, needle, options).map { SearchMatch(page, it.first, it.last + 1) }
                pagesSearched = page + 1
                if (hits.isNotEmpty()) {
                    found += hits
                    matches = found.toList()
                    // Start at the first match from the reader's current page onwards.
                    if (currentIndex < 0 && page >= fromPage) {
                        currentIndex = matches.size - hits.size
                        listener.onSearchUpdated()
                        listener.onCurrentMatchChanged(matches[currentIndex])
                    } else {
                        listener.onSearchUpdated()
                    }
                } else if (page % PROGRESS_EVERY_PAGES == 0) {
                    listener.onSearchUpdated()
                }
            }
            searching = false
            if (currentIndex < 0 && matches.isNotEmpty()) {
                currentIndex = 0
                listener.onSearchUpdated()
                listener.onCurrentMatchChanged(matches[0])
            } else {
                listener.onSearchUpdated()
            }
        }
    }

    fun next() = step(1)

    fun previous() = step(-1)

    private fun step(delta: Int) {
        if (matches.isEmpty()) return
        select(Math.floorMod(currentIndex + delta, matches.size))
    }

    fun select(index: Int) {
        if (index !in matches.indices) return
        currentIndex = index
        listener.onSearchUpdated()
        listener.onCurrentMatchChanged(matches[index])
    }

    fun matchesOn(page: Int): List<SearchMatch> = matches.filter { it.page == page }

    fun clear() {
        job?.cancel()
        query = ""
        matches = emptyList()
        currentIndex = -1
        searching = false
        listener.onSearchUpdated()
    }

    private companion object {
        const val PROGRESS_EVERY_PAGES = 10
    }
}
