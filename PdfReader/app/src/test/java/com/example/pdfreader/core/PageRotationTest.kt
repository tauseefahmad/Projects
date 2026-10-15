package com.example.pdfreader.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRotationTest {

    private val w = 600f
    private val h = 800f

    @Test
    fun displaySizeSwapsForQuarterTurns() {
        assertEquals(600f, PageRotation(w, h, 0).displayWidth)
        assertEquals(800f, PageRotation(w, h, 90).displayWidth)
        assertEquals(600f, PageRotation(w, h, 90).displayHeight)
        assertEquals(600f, PageRotation(w, h, 180).displayWidth)
    }

    @Test
    fun topLeftCornerMovesClockwise() {
        // The page's top-left corner ends up top-right after 90°, bottom-right after 180°, bottom-left after 270°.
        assertEquals(800f to 0f, PageRotation(w, h, 90).toDisplay(0f, 0f))
        assertEquals(600f to 800f, PageRotation(w, h, 180).toDisplay(0f, 0f))
        assertEquals(0f to 600f, PageRotation(w, h, 270).toDisplay(0f, 0f))
    }

    @Test
    fun fromDisplayInvertsToDisplay() {
        for (degrees in listOf(0, 90, 180, 270)) {
            val rotation = PageRotation(w, h, degrees)
            val (dx, dy) = rotation.toDisplay(123f, 456f)
            assertEquals("at $degrees°", 123f to 456f, rotation.fromDisplay(dx, dy))
        }
    }

    @Test
    fun rectanglesStayNormalized() {
        val rect = PageRotation(w, h, 90).rectToDisplay(10f, 20f, 110f, 40f)
        assertArrayEquals(floatArrayOf(760f, 10f, 780f, 110f), rect, 0.001f)
    }

    @Test
    fun degreesAreNormalized() {
        assertEquals(270, PageRotation.normalize(-90))
        assertEquals(0, PageRotation.normalize(360))
        assertEquals(90, PageRotation.normalize(450))
        assertEquals(90, PageRotation.normalize(100))
    }
}
