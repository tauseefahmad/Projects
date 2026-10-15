package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {

    @Test
    fun parsesSinglePagesAndRanges() {
        assertEquals(listOf(0..4, 5..9, 11..11), PageRanges.parse("1-5, 6-10, 12", 20))
    }

    @Test
    fun openEndedRanges() {
        assertEquals(listOf(7..19), PageRanges.parse("8-", 20))
        assertEquals(listOf(0..2), PageRanges.parse("-3", 20))
        assertEquals(listOf(1..3), PageRanges.parse("2–4", 20))
    }

    @Test
    fun rejectsBadInput() {
        assertThrows(PageRangeException::class.java) { PageRanges.parse("", 5) }
        assertThrows(PageRangeException::class.java) { PageRanges.parse("abc", 5) }
        assertThrows(PageRangeException::class.java) { PageRanges.parse("0", 5) }
        assertThrows(PageRangeException::class.java) { PageRanges.parse("4-9", 5) }
        assertThrows(PageRangeException::class.java) { PageRanges.parse("4-2", 5) }
    }

    @Test
    fun everyNPages() {
        assertEquals(listOf(0..2, 3..5, 6..6), PageRanges.everyN(7, 3))
        assertEquals(listOf(0..0, 1..1), PageRanges.everyN(2, 1))
    }

    @Test
    fun rangesFromBookmarkStarts() {
        assertEquals(listOf(0..2, 3..6, 7..9), PageRanges.fromStarts(10, listOf(3, 7)))
        // Duplicates, unordered and out-of-range starts are tolerated; page 1 always starts a part.
        assertEquals(listOf(0..4, 5..9), PageRanges.fromStarts(10, listOf(5, 5, 0, 42)))
    }

    @Test
    fun describeMergesConsecutivePages() {
        assertEquals("1–3, 5, 7–8", PageRanges.describe(listOf(0, 1, 2, 4, 6, 7)))
        assertEquals("4", PageRanges.describe(setOf(3)))
        assertEquals("", PageRanges.describe(emptyList()))
    }
}

class PageListEditorTest {

    private fun editor(n: Int) = PageListEditor(List(n) { PageRef(0, it) })
    private fun PageListEditor.order() = pages.map { it.page }

    @Test
    fun moveSinglePage() {
        val e = editor(5)
        e.move(setOf(0), 3)
        assertEquals(listOf(1, 2, 3, 0, 4), e.order())
    }

    @Test
    fun moveSeveralPagesKeepsTheirOrder() {
        val e = editor(6)
        e.move(setOf(4, 1), 0)
        assertEquals(listOf(1, 4, 0, 2, 3, 5), e.order())
        e.move(setOf(0, 1), 10)
        assertEquals(listOf(0, 2, 3, 5, 1, 4), e.order())
    }

    @Test
    fun rotateDeleteDuplicate() {
        val e = editor(4)
        e.rotate(setOf(1), 90)
        e.rotate(setOf(1), 270)
        assertEquals(0, e.pages[1].rotation)
        e.rotate(setOf(2), 180)
        assertEquals(180, e.pages[2].rotation)
        e.delete(setOf(0, 3))
        assertEquals(listOf(1, 2), e.order())
        e.duplicate(setOf(1))
        assertEquals(listOf(1, 2, 2), e.order())
    }

    @Test
    fun insertBlankAndPagesFromAnotherFile() {
        val e = editor(2)
        e.insert(1, listOf(PageRef.blank(595f, 842f)))
        e.insert(99, listOf(PageRef(1, 0), PageRef(1, 1)))
        assertTrue(e.pages[1].isBlank)
        assertEquals(listOf(0, -1, 1, 0, 1), e.order())
        assertEquals(listOf(0, -1, 0, 1, 1), e.pages.map { it.source })
    }

    @Test
    fun undoAndRedoEveryStep() {
        val e = editor(3)
        assertFalse(e.canUndo)
        e.delete(setOf(0))
        e.move(setOf(1), 0)
        assertEquals(listOf(2, 1), e.order())
        e.undo()
        assertEquals(listOf(1, 2), e.order())
        e.undo()
        assertEquals(listOf(0, 1, 2), e.order())
        assertFalse(e.canUndo)
        e.redo()
        assertEquals(listOf(1, 2), e.order())
        e.rotate(setOf(0), 90)
        assertFalse("a new edit clears redo", e.canRedo)
    }

    @Test
    fun noOpEditsAreNotRecorded() {
        val e = editor(3)
        e.move(setOf(0), 0)
        e.delete(emptySet())
        assertFalse(e.canUndo)
        assertFalse(e.isModified)
    }

    @Test
    fun selectedPagesComeInDocumentOrder() {
        val e = editor(5)
        assertEquals(listOf(1, 3), e.selected(setOf(3, 1)).map { it.page })
    }
}
