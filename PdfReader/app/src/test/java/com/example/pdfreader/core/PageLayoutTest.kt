package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadsTest {

    @Test
    fun singlePagesMakeOneItemPerPage() {
        val spreads = Spreads(3, SpreadMode.NONE)
        assertEquals(listOf(0..0, 1..1, 2..2), spreads.items)
    }

    @Test
    fun twoPageSpreadPairsPagesAndLeavesOddLastPageAlone() {
        val spreads = Spreads(5, SpreadMode.TWO_PAGE)
        assertEquals(listOf(0..1, 2..3, 4..4), spreads.items)
    }

    @Test
    fun coverModeShowsFirstPageAlone() {
        val spreads = Spreads(5, SpreadMode.TWO_PAGE_COVER)
        assertEquals(listOf(0..0, 1..2, 3..4), spreads.items)
    }

    @Test
    fun emptyDocumentHasNoItems() {
        assertEquals(0, Spreads(0, SpreadMode.TWO_PAGE_COVER).size)
    }

    @Test
    fun itemForPageFindsContainingSpread() {
        val spreads = Spreads(6, SpreadMode.TWO_PAGE_COVER) // [0] [1,2] [3,4] [5]
        assertEquals(0, spreads.itemForPage(0))
        assertEquals(1, spreads.itemForPage(1))
        assertEquals(1, spreads.itemForPage(2))
        assertEquals(2, spreads.itemForPage(4))
        assertEquals(3, spreads.itemForPage(5))
        assertEquals(3, spreads.itemForPage(99))
        assertEquals(0, spreads.itemForPage(-3))
    }
}

class PageFitTest {

    private val a4 = PageBox(595, 842)

    @Test
    fun singlePageFillsWidth() {
        val (box) = PageFit.fit(listOf(a4), availableWidth = 1000)
        assertEquals(1000, box.width)
        assertEquals(1415, box.height)
    }

    @Test
    fun spreadSharesHeightAndFillsWidthWithGap() {
        val boxes = PageFit.fit(listOf(a4, a4), availableWidth = 1010, gap = 10)
        assertEquals(500, boxes[0].width)
        assertEquals(boxes[0].height, boxes[1].height)
        assertEquals(1010, boxes.sumOf { it.width } + 10)
    }

    @Test
    fun mixedSizesInSpreadKeepTheirAspectRatios() {
        val landscape = PageBox(842, 595)
        val boxes = PageFit.fit(listOf(a4, landscape), availableWidth = 1000)
        assertEquals(boxes[0].height, boxes[1].height)
        assertEquals(842.0 / 595, boxes[1].width.toDouble() / boxes[1].height, 0.01)
    }

    @Test
    fun singlePageModeAlsoFitsHeight() {
        val (box) = PageFit.fit(listOf(a4), availableWidth = 1000, availableHeight = 700)
        assertEquals(700, box.height)
        assertEquals(495, box.width)
    }
}

class NavigationTest {

    @Test
    fun fractionMapsToPagesAtEnds() {
        assertEquals(0, Navigation.pageForFraction(0f, 240))
        assertEquals(239, Navigation.pageForFraction(1f, 240))
        assertEquals(120, Navigation.pageForFraction(0.5f, 241))
        assertEquals(0, Navigation.pageForFraction(0.7f, 1))
        assertEquals(239, Navigation.pageForFraction(5f, 240))
    }

    @Test
    fun fractionRoundTrips() {
        for (page in 0 until 50) {
            assertEquals(page, Navigation.pageForFraction(Navigation.fractionForPage(page, 50), 50))
        }
    }

    @Test
    fun parsesPageInput() {
        assertEquals(11, Navigation.parsePageInput(" 12 ", 240))
        assertEquals(239, Navigation.parsePageInput("999", 240))
        assertEquals(0, Navigation.parsePageInput("0", 240))
        assertNull(Navigation.parsePageInput("abc", 240))
        assertNull(Navigation.parsePageInput("", 240))
    }
}
