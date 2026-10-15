package com.example.pdfreader.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextGeometryTest {

    // "ab cd" on one line (y 10..20), then "\n", then "ef" on the next line (y 30..40).
    private val text = "ab cd\nef"
    private val nan = Float.NaN
    private val boxes = floatArrayOf(
        0f, 10f, 10f, 20f, // a
        10f, 10f, 20f, 20f, // b
        nan, 0f, 0f, 0f, // space
        30f, 10f, 40f, 20f, // c
        40f, 10f, 50f, 20f, // d
        nan, 0f, 0f, 0f, // newline
        0f, 30f, 10f, 40f, // e
        10f, 30f, 20f, 40f, // f
    )

    @Test
    fun pointInsideABoxHitsThatCharacter() {
        assertEquals(1, TextGeometry.indexAt(boxes, 15f, 15f))
        assertEquals(7, TextGeometry.indexAt(boxes, 15f, 35f))
    }

    @Test
    fun pointBesideALinePicksNearestOnThatLine() {
        assertEquals(4, TextGeometry.indexAt(boxes, 90f, 15f))
        // Far right of line 2 is still on line 2, even though line 1's "d" is horizontally closer.
        assertEquals(7, TextGeometry.indexAt(boxes, 60f, 36f))
    }

    @Test
    fun separatorsAreNeverHit() {
        assertEquals(1, TextGeometry.indexAt(boxes, 24f, 15f))
    }

    @Test
    fun emptyPageHasNoHit() {
        assertEquals(-1, TextGeometry.indexAt(FloatArray(0), 1f, 1f))
    }

    @Test
    fun wordAtExpandsToWordBoundaries() {
        assertEquals(0..1, TextGeometry.wordAt(text, 1))
        assertEquals(3..4, TextGeometry.wordAt(text, 3))
        assertEquals(2..2, TextGeometry.wordAt(text, 2))
    }

    @Test
    fun wordAtKeepsInnerApostrophesAndHyphens() {
        val s = "it's well-known -"
        assertEquals("it's", s.substring(TextGeometry.wordAt(s, 1)))
        assertEquals("well-known", s.substring(TextGeometry.wordAt(s, 7).let { it.first..it.last }))
    }

    @Test
    fun rangeBetweenOrdersEnds() {
        assertEquals(2..7, TextGeometry.rangeBetween(7, 2))
        assertEquals(2..7, TextGeometry.rangeBetween(2, 7))
    }
}
