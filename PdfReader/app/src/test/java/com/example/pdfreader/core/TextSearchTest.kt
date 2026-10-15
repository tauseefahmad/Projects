package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private val text = "Pineapple is not an apple. APPLE pie and apple-tart."

    private fun count(options: SearchOptions, query: String = "apple") = TextSearch.find(text, query, options).size

    @Test
    fun ignoresCaseByDefaultAndMatchesInsideWords() {
        assertEquals(4, count(SearchOptions()))
    }

    @Test
    fun matchCase() {
        assertEquals(3, count(SearchOptions(matchCase = true)))
    }

    @Test
    fun wholeWordSkipsPartsOfLongerWords() {
        // "Pineapple" is excluded; "apple-tart" counts because '-' is a word break.
        assertEquals(3, count(SearchOptions(wholeWord = true)))
        assertEquals(2, count(SearchOptions(matchCase = true, wholeWord = true)))
    }

    @Test
    fun returnsCharacterRanges() {
        val ranges = TextSearch.find("an apple", "apple", SearchOptions())
        assertEquals(listOf(3..7), ranges)
    }

    @Test
    fun blankQueryFindsNothing() {
        assertEquals(0, count(SearchOptions(), "  "))
    }

    @Test
    fun matchesDoNotOverlap() {
        assertEquals(2, TextSearch.find("aaaa", "aa", SearchOptions()).size)
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(4, count(SearchOptions(), " apple "))
    }

    @Test
    fun snippetKeepsContextAndMarksMatch() {
        val long = "The quick brown fox jumps over the lazy dog and then the fox sleeps soundly all night long."
        val start = long.indexOf("lazy")
        val snippet = TextSearch.snippet(long, start, start + 4, radius = 15)
        assertEquals("lazy", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue(snippet.text, snippet.text.startsWith("…"))
        assertTrue(snippet.text, snippet.text.endsWith("…"))
    }

    @Test
    fun snippetFlattensLineBreaks() {
        val snippet = TextSearch.snippet("first line\nsecond line", 11, 17)
        assertEquals("first line second line", snippet.text)
        assertEquals("second", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun firstFromPageWrapsAround() {
        val matches = listOf(SearchMatch(1, 0, 1), SearchMatch(4, 0, 1), SearchMatch(4, 5, 6))
        assertEquals(1, TextSearch.firstFromPage(matches, 3))
        assertEquals(0, TextSearch.firstFromPage(matches, 0))
        assertEquals(0, TextSearch.firstFromPage(matches, 9))
        assertEquals(-1, TextSearch.firstFromPage(emptyList(), 0))
    }
}
