package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationToolsTest {

    private fun text(id: String, page: Int, top: Float, text: String, note: String = "") =
        Annotation.FreeText(id, page, 0xFF000000.toInt(), "Ann", 0, note, Box(10f, top, 110f, top + 20f), text)

    @Test
    fun resizingABoxScalesIt() {
        val a = text("a", 0, 0f, "hi")
        val r = AnnotationTransform.resized(a, Box(10f, 0f, 210f, 40f)) as Annotation.FreeText
        assertEquals(Box(10f, 0f, 210f, 40f), r.box)
    }

    @Test
    fun resizingInkScalesEveryPoint() {
        val ink = Annotation.Ink("i", 0, 0, "", 0, strokes = listOf(listOf(Pt(0f, 0f), Pt(10f, 10f))), width = 0f)
        val r = AnnotationTransform.resized(ink, Box(0f, 0f, 20f, 40f)) as Annotation.Ink
        assertEquals(listOf(Pt(0f, 0f), Pt(20f, 40f)), r.strokes[0])
    }

    @Test
    fun resizingNeverGoesBelowTheMinimumSize() {
        val r = AnnotationTransform.resized(text("a", 0, 0f, "hi"), Box(10f, 0f, 11f, 1f))
        assertEquals(AnnotationTransform.MIN_SIZE, r.bounds.width, 0.001f)
        assertEquals(AnnotationTransform.MIN_SIZE, r.bounds.height, 0.001f)
    }

    @Test
    fun notesAreOnlyMovedNotResized() {
        val note = Annotation.Note("n", 0, 0, "", 0, "x", Pt(5f, 5f))
        val r = AnnotationTransform.resized(note, Box(50f, 60f, 200f, 200f)) as Annotation.Note
        assertEquals(Pt(50f, 60f), r.at)
        assertFalse(AnnotationTransform.canResize(note))
    }

    @Test
    fun palmRejectionStopsFingersAfterAStylus() {
        val palm = PalmRejection()
        assertTrue(palm.draws(PointerKind.FINGER))
        assertTrue(palm.draws(PointerKind.STYLUS))
        assertFalse(palm.draws(PointerKind.FINGER))
        palm.fingerDrawing = true
        assertTrue(palm.draws(PointerKind.FINGER))
    }

    @Test
    fun pressureChangesWidthButConstantPressureDoesNot() {
        assertEquals(2f, PenPressure.width(2f, listOf(1f, 1f, 1f)), 0.001f)
        assertTrue(PenPressure.width(2f, listOf(0.1f, 0.2f)) < 2f)
        assertTrue(PenPressure.width(2f, listOf(0.9f, 1f)) > 2f)
        assertEquals(2f * 1.8f, PenPressure.width(2f, listOf(1f, 1f, 0.99f, 1f)), 0.1f)
    }

    @Test
    fun filterByTypeAndAuthor() {
        val items = listOf(text("a", 0, 0f, "x"), Annotation.Note("n", 0, 0, "Bob", 0, "y", Pt(0f, 0f)))
        assertEquals(listOf("n"), AnnotationFilter(types = setOf("Note")).apply(items).map { it.id })
        assertEquals(listOf("a"), AnnotationFilter(authors = setOf("Ann")).apply(items).map { it.id })
        assertEquals(2, AnnotationFilter().apply(items).size)
    }

    @Test
    fun summaryListsAnnotationsByPageInReadingOrder() {
        val items = listOf(
            text("b", 1, 0f, "second page"),
            text("a2", 0, 100f, "lower", note = "check this"),
            text("a1", 0, 10f, "upper"),
        )
        val summary = AnnotationSummary.text("doc.pdf", items) { "today" }
        val expected = """
            Annotations — doc.pdf
            3 annotations

            Page 1
            • Text box (Ann, today): “upper”
            • Text box (Ann, today): “lower”
                check this

            Page 2
            • Text box (Ann, today): “second page”
        """.trimIndent() + "\n"
        // modified = 0 means "unknown date"; these have 0, so no date is shown.
        assertEquals(expected.replace(", today", ""), summary)
    }
}
