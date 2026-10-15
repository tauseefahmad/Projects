package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationsTest {

    private fun highlight(id: String, page: Int = 0, box: Box = Box(10f, 10f, 100f, 20f)) =
        Annotation.Markup(id, page, 0xFFFFEB3B.toInt(), "me", 0, kind = MarkupKind.HIGHLIGHT, lines = listOf(box))

    @Test
    fun historyUndoesAndRedoesEveryChange() {
        val history = AnnotationHistory()
        history.add(highlight("a"))
        history.add(highlight("b", page = 1))
        history.replace(highlight("a").withColor(0xFF00FF00.toInt()))
        history.remove("b")
        assertEquals(listOf("a"), history.items.map { it.id })
        assertEquals(0xFF00FF00.toInt(), history.items[0].color)
        history.undo()
        assertEquals(listOf("a", "b"), history.items.map { it.id })
        history.undo()
        assertEquals(0xFFFFEB3B.toInt(), history.find("a")!!.color)
        history.redo()
        assertEquals(0xFF00FF00.toInt(), history.find("a")!!.color)
        assertTrue(history.isModified)
        history.markSaved()
        assertFalse(history.isModified)
    }

    @Test
    fun hitTestingFindsTopmostAnnotationOnThePage() {
        val history = AnnotationHistory(listOf(highlight("under"), highlight("over"), highlight("other page", page = 2)))
        assertEquals("over", history.hit(0, 50f, 15f)!!.id)
        assertNull(history.hit(0, 50f, 60f))
        assertNull(history.hit(1, 50f, 15f))
    }

    @Test
    fun inkIsHitNearItsStrokeOnly() {
        val ink = Annotation.Ink("i", 0, 0, "", 0, strokes = listOf(listOf(Pt(0f, 0f), Pt(100f, 0f))), width = 2f)
        val history = AnnotationHistory(listOf(ink))
        assertEquals("i", history.hit(0, 50f, 3f)!!.id)
        assertNull(history.hit(0, 50f, 30f))
    }

    @Test
    fun movingShiftsGeometry() {
        val note = Annotation.Note("n", 0, 0, "", 0, "hi", Pt(10f, 10f)).moved(5f, -5f) as Annotation.Note
        assertEquals(Pt(15f, 5f), note.at)
        val shape = Annotation.Shape("s", 0, 0, "", 0, kind = ShapeKind.LINE, points = listOf(Pt(0f, 0f), Pt(10f, 10f))).moved(1f, 2f)
        assertEquals(Box(1f - 2f, 2f - 2f, 11f + 2f, 12f + 2f), shape.bounds)
    }

    @Test
    fun smoothingThinsAndAveragesPoints() {
        val jagged = listOf(Pt(0f, 0f), Pt(0.1f, 0f), Pt(10f, 4f), Pt(20f, -4f), Pt(30f, 0f))
        val smooth = Geometry.smooth(jagged)
        assertEquals(Pt(0f, 0f), smooth.first())
        assertEquals(Pt(30f, 0f), smooth.last())
        assertTrue("jitter reduced", smooth.drop(1).dropLast(1).all { kotlin.math.abs(it.y) < 4f })
    }

    @Test
    fun distanceToSegment() {
        assertEquals(5f, Geometry.distanceToSegment(5f, 5f, Pt(0f, 0f), Pt(10f, 0f)), 0.001f)
        assertEquals(5f, Geometry.distanceToSegment(-3f, 4f, Pt(0f, 0f), Pt(10f, 0f)), 0.001f)
    }

    @Test
    fun arrowHeadPointsBackFromTheTip() {
        val (a, b) = Geometry.arrowHead(Pt(0f, 0f), Pt(100f, 0f), 10f)
        assertTrue(a.x < 100f && b.x < 100f)
        assertEquals(-a.y, b.y, 0.001f)
    }

    @Test
    fun typeNames() {
        assertEquals("Highlight", highlight("a").typeName())
        assertEquals("Highlighter", Annotation.Ink("i", 0, 0, "", 0, strokes = listOf(listOf(Pt(0f, 0f))), width = 8f, opacity = 0.4f).typeName())
        assertEquals("Arrow", Annotation.Shape("s", 0, 0, "", 0, kind = ShapeKind.ARROW, points = listOf(Pt(0f, 0f), Pt(1f, 1f))).typeName())
    }
}

class AnnotationJsonTest {

    @Test
    fun everyKindRoundTrips() {
        val items = listOf(
            Annotation.Markup("m", 0, 1, "Sara", 5, "nice", MarkupKind.SQUIGGLY, listOf(Box(1f, 2f, 3f, 4f)), "text"),
            Annotation.Note("n", 1, 2, "", 6, "remember", Pt(5f, 6f)),
            Annotation.FreeText("f", 2, 3, "", 7, "", Box(0f, 0f, 100f, 40f), "Hello\\nworld", 14f),
            Annotation.Ink("i", 0, 4, "", 8, "", listOf(listOf(Pt(0f, 0f), Pt(1f, 1f))), 3f, 0.4f),
            Annotation.Shape("s", 0, 5, "", 9, "", ShapeKind.POLYGON, listOf(Pt(0f, 0f), Pt(5f, 0f), Pt(0f, 5f)), 2f, fill = 7),
            Annotation.Stamp("st", 3, 6, "", 10, "", Box(0f, 0f, 150f, 50f), StampKind.PAID, "PAID", "15 Oct 2026 · Sara"),
            Annotation.Image("im", 4, 0, "", 11, "", Box(0f, 0f, 50f, 50f), "/data/x.png"),
        )
        assertEquals(items, AnnotationJson.decode(AnnotationJson.encode(items)))
    }

    @Test
    fun brokenJsonGivesNoAnnotations() {
        assertEquals(emptyList<Annotation>(), AnnotationJson.decode("{not json"))
    }
}
