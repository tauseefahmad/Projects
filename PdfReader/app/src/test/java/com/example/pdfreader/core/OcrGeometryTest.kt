package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrGeometryTest {

    /** Turns a pixel of a w×h picture clockwise by [turn], the way Matrix.postRotate + translate does. */
    private fun turn(p: Pt, turn: Int, w: Float, h: Float): Pt = when (turn) {
        90 -> Pt(h - p.y, p.x)
        180 -> Pt(w - p.x, h - p.y)
        270 -> Pt(p.y, w - p.x)
        else -> p
    }

    @Test
    fun unturnUndoesATurn() {
        val p = Pt(30f, 10f)
        for (t in listOf(0, 90, 180, 270)) {
            val turned = turn(p, t, 100f, 50f)
            assertEquals("turn $t", p, OcrGeometry.unturn(turned.x, turned.y, t, 100f, 50f))
        }
    }

    @Test
    fun displayedPointsMapBackToTheUnrotatedPage() {
        // A 600 × 800 page shown turned 90°: the display is 800 × 600 and its top-left is the page's bottom-left.
        assertEquals(800f to 600f, OcrGeometry.displaySize(90, 600f, 800f))
        assertEquals(Pt(0f, 800f), OcrGeometry.displayToPage(0f, 0f, 90, 600f, 800f))
        assertEquals(Pt(0f, 0f), OcrGeometry.displayToPage(800f, 0f, 90, 600f, 800f))
        assertEquals(Pt(600f, 800f), OcrGeometry.displayToPage(0f, 0f, 180, 600f, 800f))
        assertEquals(Pt(600f, 0f), OcrGeometry.displayToPage(0f, 0f, 270, 600f, 800f))
        assertEquals(Pt(5f, 7f), OcrGeometry.displayToPage(5f, 7f, 0, 600f, 800f))
    }

    @Test
    fun textAngleCombinesTurnAndRotation() {
        assertEquals(0, OcrGeometry.textAngle(0, 0))
        assertEquals(90, OcrGeometry.textAngle(90, 0))
        assertEquals(0, OcrGeometry.textAngle(270, 90))
        assertEquals(270, OcrGeometry.textAngle(-90, 0))
    }
}
